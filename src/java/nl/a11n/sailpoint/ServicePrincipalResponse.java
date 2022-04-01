package nl.a11n.sailpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ServicePrincipalResponse {
	
	List<ServicePrincipalResponseValue> value;

	static class ServicePrincipalResponseAppRole {
		String description, displayName, id, origin, value;
		Boolean isEnabled;
		List<String> allowedMemberTypes;
		String servicePrincipalId;
		String servicePrincipalName;

		public static String formatAppRoleId(String servicePrincipalId, String id){
			return String.format("%s_%s", servicePrincipalId, id);
		}

		public String getId() {
			return ServicePrincipalResponseAppRole.formatAppRoleId(this.servicePrincipalId, this.id);
		}

		public String getDisplayableName() {
			return String.format("%s_%s", this.servicePrincipalName, this.displayName);
		}

		public Map<String, Object> toMap() {
			HashMap<String, Object> map = new HashMap<>();
			map.put("description", description);
			map.put("displayName", displayName);
			map.put("displayableName", this.getDisplayableName());
			map.put("id", this.getId());
			map.put("origin", origin);
			map.put("value", value);
			map.put("isEnabled", isEnabled);
			map.put("servicePrincipalId", servicePrincipalId);
			map.put("servicePrincipalName", servicePrincipalName);
			return map;
		}
	}

	static class ServicePrincipalResponseValue {
		List<ServicePrincipalResponseAppRole> appRoles;
		String id, appId, displayName;
	}
	
}
