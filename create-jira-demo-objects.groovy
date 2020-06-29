import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.component.ComponentAccessor 
import com.atlassian.jira.security.roles.ProjectRoleManager 
import com.atlassian.jira.security.roles.ProjectRoleActors 
import com.atlassian.jira.bc.issue.IssueService 
import com.atlassian.jira.issue.IssueInputParameters 
import com.atlassian.jira.user.ApplicationUser 
import com.atlassian.jira.bc.issue.IssueService 
import com.atlassian.jira.bc.issue.IssueService.CreateValidationResult 
import com.atlassian.jira.bc.issue.IssueService.IssueResult 
import com.atlassian.jira.issue.issuetype.IssueType 
import com.atlassian.jira.issue.fields.CustomField 
import com.atlassian.jira.issue.ModifiedValue 
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder 

def pm = ComponentAccessor.getProjectManager() 
def im = ComponentAccessor.getIssueManager() 
def issueService = ComponentAccessor.getIssueService() 
def itm = ComponentAccessor.getIssueTypeSchemeManager() 
def cfm = ComponentAccessor.getCustomFieldManager() 
def lm = ComponentAccessor.getIssueLinkManager() 
def um = ComponentAccessor.getUserManager() 

def pscrum = pm.getProjectByCurrentKey("SCRUM") 
def pkanban = pm.getProjectByCurrentKey("KANBAN") 
def issueTypes = itm.getIssueTypesForProject(pscrum) 
def IssueType epicIssueType 
def IssueType storyIssueType 
def IssueType subTaskIssueType 
def IssueType taskIssueType 
def IssueType bugIssueType 

for (type in issueTypes) { 
    switch (type.getName()) { 
        case "Epic": 
        epicIssueType = type 
        break; 
        case "Task": 
        taskIssueType = type 
        break; 
        case "Sub-task": 
        subTaskIssueType = type 
        break; 
        case "Story": 
        storyIssueType = type 
        break; 
        case "Bug": 
        bugIssueType = type 
        break; 
    } 
} 

ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser() 
def ArrayList<ApplicationUser> devUsers = new ArrayList<ApplicationUser>() 
devUsers.add(um.getUserByName("dev1")) 
devUsers.add(um.getUserByName("dev2")) 
devUsers.add(um.getUserByName("dev3")) 
devUsers.add(um.getUserByName("dev4")) 
devUsers.add(um.getUserByName("dev5")) 
def champDateDebut = cfm.getCustomFieldObject(10340) 
for (def epicIndex = 1; epicIndex <= 5; epicIndex++) { 
    IssueInputParameters issueInputParameters = issueService.newIssueInputParameters(); 
    issueInputParameters
    .setProjectId(pscrum.getId()) 
    .setSummary("Épopée " + epicIndex) 
    .setDescription("Épopée " + epicIndex) 
    .setIssueTypeId(epicIssueType.getId()) 
    .setReporterId(user.getName()) 
    .addCustomFieldValue(10102,"Épopée " + epicIndex) 

    CreateValidationResult createValidationResult = issueService.validateCreate(user, issueInputParameters) 
    if (createValidationResult.isValid()) { 
        IssueResult createResult = issueService.create(user, createValidationResult) 
        if (!createResult.isValid()) { 
            log.error("Error while creating the issue.") 
        } else { 
            def epicIssue = createResult.getIssue() 
            def dayIndex = 1 
            for (def storyIndex = 1; storyIndex <= 10; storyIndex++) { 
                issueInputParameters = issueService.newIssueInputParameters(); 
                CustomField epicLink = cfm.getCustomFieldObjectByName('Epic Link'); 
                ApplicationUser assignee = devUsers.get(storyIndex % devUsers.size()) 
                issueInputParameters 
                .setProjectId(pscrum.getId()) 
                .setSummary("Récit " + storyIndex) 
                .setDescription("Récit " + storyIndex) 
                .setIssueTypeId(storyIssueType.getId()) 
                .setReporterId(user.getName()) 
                .setAssigneeId(assignee.getName()) 

                createValidationResult = issueService.validateCreate(user, issueInputParameters) 
                if (createValidationResult.isValid()) { 
                    createResult = issueService.create(user, createValidationResult) 
                    if (!createResult.isValid()) { 
                        log.error("Error while creating the issue.") 
                    } else { 
                        def storyIssue = createResult.getIssue() 
                        storyIssue.setCustomFieldValue(epicLink, epicIssue) 
                        if (epicLink) { 
                            def changeHolder = new DefaultIssueChangeHolder() 
                            epicLink.updateValue(null, storyIssue, new ModifiedValue(storyIssue.getCustomFieldValue(epicLink), epicIssue),changeHolder) 
                        } 
                        dayIndex = 1 
                        for (def subTaskIndex = 1; subTaskIndex <= 3; subTaskIndex++) { 
                            dayIndex++ 
                                issueInputParameters 
                            	.setProjectId(pscrum.getId()) 
                            	.setSummary("Sous-tâche " + subTaskIndex) 
                            	.setDescription("Sous-tâche " + subTaskIndex) 
                            	.setIssueTypeId(subTaskIssueType.getId()) 
                            	.setReporterId(user.getName()) 
                            	.setDueDate(dayIndex + "/mars/20") 
                            	.setOriginalEstimate("2h") 
                            	.setAssigneeId(assignee.getName()) 
                            	.addCustomFieldValue(champDateDebut.id, dayIndex + "/mars/20") 
                            
                            createValidationResult = issueService.validateSubTaskCreate(user, storyIssue.getId(), issueInputParameters) 
                            if (createValidationResult.isValid()) { 
                                createResult = issueService.create(user, createValidationResult) 
                                if (!createResult.isValid()) { 
                                    log.error("Error while creating the issue.") 
                                } else { 
                                    ComponentAccessor.subTaskManager.createSubTaskIssueLink(storyIssue, createResult.getIssue(), user) 
                                } 
                            } else { 
                                log.error(createValidationResult.getErrorCollection()) 
                            } 
                        } 
                    } 
                } else { 
                    log.error(createValidationResult.getErrorCollection()) 
                } 
            } 
        } 
    } else { 
        log.error(createValidationResult.getErrorCollection()) 
    } 
} 

for (def taskIndex = 1; taskIndex <= 5; taskIndex++) { 
    IssueInputParameters issueInputParameters = issueService.newIssueInputParameters(); 
    issueInputParameters 
    .setProjectId(pscrum.getId()) 
    .setSummary("Tâche " + taskIndex) 
    .setDescription("Tâche " + taskIndex) 
    .setIssueTypeId(taskIssueType.getId()) 
    .setReporterId(user.getName()) 
    .setDueDate(taskIndex + "/mars/20") 
    .setOriginalEstimate("2h") 
    .addCustomFieldValue(champDateDebut.id, taskIndex + "/mars/20") 
    
    CreateValidationResult createValidationResult = issueService.validateCreate(user, issueInputParameters) 
    if (createValidationResult.isValid()) { 
        IssueResult createResult = issueService.create(user, createValidationResult) 
        if (!createResult.isValid()) { 
            log.error("Error while creating the issue.") 
        } 
    } else { 
        log.error(createValidationResult.getErrorCollection()) 
    } 
}