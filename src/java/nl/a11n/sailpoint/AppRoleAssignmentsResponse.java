package nl.a11n.sailpoint;

import java.util.List;


public class AppRoleAssignmentsResponse {
	
	List<AppRoleAssignmentsResponseValue> value;

	static class AppRoleAssignmentsResponseValue {
		String id, deletedDateTime, appRoleId, createdDateTime, principalDisplayName, principalId, principalType, resourceDisplayName, resourceId;
	}
	
}
