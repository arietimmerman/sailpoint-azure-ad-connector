package nl.a11n.sailpoint;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import nl.a11n.sailpoint.AppRoleAssignmentsResponse.AppRoleAssignmentsResponseValue;
import nl.a11n.sailpoint.ServicePrincipalResponse.ServicePrincipalResponseAppRole;
import nl.a11n.sailpoint.ServicePrincipalResponse.ServicePrincipalResponseValue;
import sailpoint.connector.ConnectorException;
import sailpoint.connector.SchemaNotDefinedException;
import sailpoint.object.Application;
import sailpoint.object.AttributeDefinition;
import sailpoint.object.Filter;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.ProvisioningResult;
import sailpoint.object.ResourceObject;
import sailpoint.object.Schema;
import sailpoint.tools.CloseableIterator;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

public class AzureADConnector extends sailpoint.connector.AzureADConnector {

	protected static Logger logger = Logger.getLogger(AzureADConnector.class);

	public static HttpRequest.BodyPublisher ofFormData(Map<String, String> data) {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, String> entry : data.entrySet()) {
			if (builder.length() > 0) {
				builder.append("&");
			}
			builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
			builder.append("=");
			builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
		}
		return HttpRequest.BodyPublishers.ofString(builder.toString());
	}

	public String getAccessToken() throws ConnectorException {

		Map<String, String> map = new HashMap<>();
		map.put("client_id", this.getStringAttribute("clientID"));
		map.put("client_secret", this.getStringAttribute("clientSecret"));
		map.put("grant_type", "client_credentials");
		map.put("scope", "https://graph.microsoft.com/.default");

		HttpRequest request = HttpRequest
				.newBuilder(URI.create(
						String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token",
								this.getStringAttribute("domainName"))))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(ofFormData(map)).build();

		try {
			HttpResponse<String> response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (new Gson()).fromJson(response.body(), Map.class);

			logger.trace(String.format("access_token: %s...",
					StringUtils.substring((String) result.get("access_token"), 0, 4)));

			return (String) result.get("access_token");
		} catch (IOException | InterruptedException e) {
			throw new ConnectorException(e);
		}
	}

	static class ServicePrincipalIterator implements CloseableIterator<ResourceObject> {

		String accessToken;
		Iterator<ServicePrincipalResponse.ServicePrincipalResponseAppRole> permissions;

		protected ServicePrincipalIterator(String accessToken) {
			this.accessToken = accessToken;
		}

		public static ServicePrincipalIterator init(String accessToken) throws ConnectorException {
			return (new ServicePrincipalIterator(accessToken)).loadObjects();
		}

		public ServicePrincipalIterator loadObjects() throws ConnectorException {

			logger.trace("start loadObjects");

			HttpRequest request = HttpRequest.newBuilder(URI.create(
					"https://graph.microsoft.com/v1.0/serviceprincipals?$top=999"))
					.header("accept", "application/json")
					.header("Authorization", String.format("Bearer %s", this.accessToken)).build();

			// use the client to send the request
			HttpResponse<String> response;

			try {
				response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
			} catch (IOException | InterruptedException e) {
				throw new ConnectorException(e);
			}

			logger.trace(String.format("response status code: %d", response.statusCode()));

			ServicePrincipalResponse result = (new Gson()).fromJson(response.body(), ServicePrincipalResponse.class);

			ArrayList<ServicePrincipalResponseAppRole> permissionList = new ArrayList<ServicePrincipalResponseAppRole>();

			Iterator<ServicePrincipalResponse.ServicePrincipalResponseValue> iterator = result.value.iterator();

			while (iterator.hasNext()) {
				ServicePrincipalResponseValue next = iterator.next();
				if (next.appRoles != null) {
					for (ServicePrincipalResponseAppRole appRole : next.appRoles) {
						appRole.servicePrincipalId = next.id;
						appRole.servicePrincipalName = next.displayName;
						permissionList.add(appRole);
					}

					// The "Default Access" application role is not returned, but can be assigned.
					ServicePrincipalResponseAppRole defaultAccess = new ServicePrincipalResponseAppRole();
					defaultAccess.servicePrincipalId = next.id;
					defaultAccess.servicePrincipalName = next.displayName;
					defaultAccess.displayName = "Default Access";
					defaultAccess.id = "00000000-0000-0000-0000-000000000000";
					permissionList.add(defaultAccess);
				}
			}

			this.permissions = permissionList.iterator();
			return this;
		}

		@Override
		public void close() {
			// not needed
		}

		@Override
		public boolean hasNext() {
			return this.permissions.hasNext();
		}

		@Override
		public ResourceObject next() {
			logger.trace("Load next");
			ServicePrincipalResponseAppRole map = this.permissions.next();
			logger.trace(map);

			return new ResourceObject(map.getId(), map.getDisplayableName(), "application_role", map.toMap());
		}

	}

	static class UserIterator implements CloseableIterator<ResourceObject> {

		private CloseableIterator<ResourceObject> iterator;
		private AppRoleManager appRoleManager;

		protected UserIterator(AzureADConnector connector, AzureADConnector.AppRoleManager appRoleManager,
				String objectType, Filter filter,
				Map<String, Object> options) throws ConnectorException {
			this.iterator = connector.superIterateObjects(objectType, filter, options);
			this.appRoleManager = appRoleManager;
		}

		@Override
		public void close() {
			this.iterator.close();
		}

		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public ResourceObject next() {
			ResourceObject next = this.iterator.next();

			List<String> application_roles = new ArrayList<>();

			try {
				List<AppRoleAssignmentsResponseValue> appRoles = this.appRoleManager.get(next.getIdentity());

				if (appRoles != null) {
					application_roles = appRoles.stream().map(
							r -> ServicePrincipalResponse.ServicePrincipalResponseAppRole.formatAppRoleId(r.resourceId,
									r.appRoleId))
							.collect(Collectors.toList());
				}
			} catch (ConnectorException e) {
				e.printStackTrace();
			}

			next.setAttribute("application_roles", application_roles);

			return next;
		}

	}

	static class AppRoleManager {

		String accessToken;

		protected AppRoleManager(String accessToken) {
			this.accessToken = accessToken;
		}

		public List<AppRoleAssignmentsResponseValue> get(String principalId) throws ConnectorException {

			HttpRequest request = HttpRequest.newBuilder(URI.create(
					String.format("https://graph.microsoft.com/v1.0/users/%s/appRoleAssignments", principalId)))
					.header("accept", "application/json")
					.header("Authorization", String.format("Bearer %s", this.accessToken)).build();

			// use the client to send the request
			HttpResponse<String> response;

			try {
				response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

				AppRoleAssignmentsResponse result = (new Gson()).fromJson(response.body(),
						AppRoleAssignmentsResponse.class);

				return result.value;
			} catch (IOException | InterruptedException e) {
				throw new ConnectorException(e);
			}

		}

		public void add(String principalId, String resourceId, String appRoleId) throws GeneralException {

			Map<String, String> map = new HashMap<>();
			map.put("principalId", principalId);
			map.put("resourceId", resourceId);
			map.put("appRoleId", appRoleId);

			ObjectMapper objectMapper = new ObjectMapper();
			String requestBody;
			try {
				requestBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
			} catch (JsonProcessingException e) {
				throw new GeneralException(e);
			}

			HttpRequest.newBuilder(URI.create(
					String.format("https://graph.microsoft.com/v1.0/users/%s/appRoleAssignments", principalId)))
					.header("accept", "application/json")
					.header("Authorization", String.format("Bearer %s", this.accessToken))
					.POST(BodyPublishers.ofString(requestBody)).build();

		}

		public void delete(String principalId, String resourceId, String appRoleId) throws ConnectorException {
			List<AppRoleAssignmentsResponseValue> roles = this.get(principalId);

			for (AppRoleAssignmentsResponseValue r : roles) {
				if (resourceId.equals(r.resourceId) && appRoleId.equals(r.appRoleId)) {
					HttpRequest.newBuilder(URI.create(
							String.format("https://graph.microsoft.com/v1.0/servicePrincipals/%s/appRoleAssignments/%s",
									resourceId, r.id)))
							.header("accept", "application/json")
							.header("Authorization", String.format("Bearer %s", this.accessToken))
							.DELETE()
							.build();
				}
			}
		}
	}

	public AzureADConnector(Application application) {
		super(application);
	}

	@Override
	public Schema getSchema(String objectType) throws SchemaNotDefinedException {
		Schema result = null;

		if ("account".equals(objectType)) {
			result = super.getSchema(objectType);
			AttributeDefinition attributeDefinition = new AttributeDefinition("application_roles",
					AttributeDefinition.TYPE_STRING);
			attributeDefinition.setMultiValued(true);
			attributeDefinition.setMulti(true);
			result.addAttributeDefinition(attributeDefinition);
		} else if ("application_role".equals(objectType)) {
			result = new Schema("application_role", "application_role");
			result.setDisplayAttribute("displayableName");
			result.setIdentityAttribute("id");
			result.addAttributeDefinition("description", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("displayableName", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("displayName", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("id", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("origin", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("value", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("isEnabled", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("servicePrincipalId", AttributeDefinition.TYPE_STRING);
			result.addAttributeDefinition("servicePrincipalName", AttributeDefinition.TYPE_STRING);
		} else {
			result = super.getSchema(objectType);
		}

		return result;
	}

	public CloseableIterator<ResourceObject> superIterateObjects(String objectType, Filter filter,
			Map<String, Object> options) throws ConnectorException {
		return super.iterateObjects(objectType, filter, options);
	}

	@Override
	public CloseableIterator<ResourceObject> iterateObjects(String objectType, Filter filter,
			Map<String, Object> options) throws ConnectorException {

		if ("application_role".equals(objectType)) {
			try {
				return ServicePrincipalIterator.init(this.getAccessToken());
			} catch (Exception e) {
				throw new ConnectorException(e);
			}
		} else if ("account".equals(objectType)) {
			return new UserIterator(this, new AppRoleManager(this.getAccessToken()), objectType, filter, options);
		} else {
			return super.iterateObjects(objectType, filter, options);
		}

	}

	@Override
	public ProvisioningResult provision(ProvisioningPlan plan) throws ConnectorException, GeneralException {
		ProvisioningResult first = super.provision(plan);

		AppRoleManager appRoleManager = new AppRoleManager(this.getAccessToken());

		for (AccountRequest accountRequest : Util.safeIterable(plan.getAccountRequests())) {
			for (AttributeRequest attributeRequest : Util
					.safeIterable(accountRequest.getAttributeRequests("application_roles"))) {
				if (ProvisioningPlan.Operation.Add.equals(attributeRequest.getOperation())) {
					for (String value : Util.otol(attributeRequest.getValue())) {
						String[] splitted = value.split("_");
						appRoleManager.add(accountRequest.getNativeIdentity(), splitted[0], splitted[1]);
					}
				} else if (ProvisioningPlan.Operation.Remove.equals(attributeRequest.getOperation())) {
					for (String value : Util.otol(attributeRequest.getValue())) {
						String[] splitted = value.split("_");
						appRoleManager.delete(accountRequest.getNativeIdentity(), splitted[0], splitted[1]);
					}
				}
			}
		}

		return first;
	}

}
