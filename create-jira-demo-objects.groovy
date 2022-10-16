import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.project.template.ProjectTemplate
import com.atlassian.jira.issue.Issue
import java.time.LocalDateTime
import com.atlassian.jira.issue.issuetype.IssueType
import common.jira.dc.ProjectUtils
import com.atlassian.jira.bc.project.ProjectCreationData.Builder
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.type.ProjectTypeKeys
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.project.template.ProjectTemplateManager
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder 
import com.atlassian.jira.issue.ModifiedValue 
import com.atlassian.greenhopper.service.sprint.SprintManager
import com.onresolve.scriptrunner.runner.customisers.JiraAgileBean
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.atlassian.greenhopper.service.sprint.Sprint
import com.atlassian.greenhopper.service.sprint.SprintIssueService
import com.atlassian.greenhopper.service.rapid.view.RapidViewQuery
import com.atlassian.greenhopper.service.rapid.view.RapidViewService
import com.atlassian.greenhopper.model.rapid.RapidView
import com.atlassian.greenhopper.manager.rapidview.RapidViewManager
import com.atlassian.greenhopper.service.rapid.view.RapidViewSimpleQuery
import groovy.transform.Field
import com.atlassian.servicedesk.api.request.ServiceDeskCustomerRequestService
import com.atlassian.servicedesk.api.ServiceDeskService
import com.atlassian.servicedesk.api.requesttype.RequestTypeService
import com.atlassian.servicedesk.api.field.RequestTypeFieldService
import com.atlassian.servicedesk.api.field.RequestTypeField
import com.atlassian.servicedesk.api.field.FieldInputValue
import com.atlassian.servicedesk.api.field.FieldId

@WithPlugin(["com.pyxis.greenhopper.jira", "com.atlassian.servicedesk"])

@Field final Long EPIC_LINK_FIELD_ID = 10002
@Field final Long EPIC_NAME_FIELD_ID = 10004

ApplicationUser loggedUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

@Field IssueService issueService = ComponentAccessor.getIssueService()
@Field ProjectTemplateManager ptm = ComponentAccessor.getComponent(ProjectTemplateManager)

def scrumTemplate = ptm.projectTemplates.find { it.projectTypeKey == ProjectTypeKeys.SOFTWARE && it.name == "Scrum software development" }
def basicJSD = ptm.projectTemplates.find { it.projectTypeKey == ProjectTypeKeys.SERVICE_DESK && it.name == "Basic" }
def kanbanTemplate = ptm.projectTemplates.find { it.projectTypeKey == ProjectTypeKeys.SOFTWARE && it.name == "Kanban software development" }

createSoftwareIssues(scrumTemplate, true, [ name: 'Scrum For Testing', key: 'SFT'], loggedUser)
createSoftwareIssues(scrumTemplate, true, [ name: 'Kanban For Testing', key: 'KFT'], loggedUser)
createServiceDeskIssues(basicJSD, [name: 'JSM Basic For Testing', key: 'JBFT'], loggedUser)

def createServiceDeskIssues(ProjectTemplate template, Map<String,String> projectInfo, ApplicationUser loggedUser) {
    ProjectUtils projectUtils = new ProjectUtils()
    def projectData = new Builder().withKey(projectInfo['key']).withLead(loggedUser).withType(ProjectTypeKeys.SERVICE_DESK).withName(projectInfo['name']).withProjectTemplateKey(template.key.key).build()
    def createOutcome = projectUtils.createProject(loggedUser, projectData, [loggedUser], null)
    def project = createOutcome.get()
    if (project) {
        def sdRequestService = ComponentAccessor.getOSGiComponentInstanceOfType(ServiceDeskCustomerRequestService)
        def sdService = ComponentAccessor.getOSGiComponentInstanceOfType(ServiceDeskService)
        def sdRequestTypeService = ComponentAccessor.getOSGiComponentInstanceOfType(RequestTypeService)
        def sdRequestTypeFieldService = ComponentAccessor.getOSGiComponentInstanceOfType(RequestTypeFieldService)

        def linkedServiceDesk = sdService.getServiceDeskForProject(loggedUser, project)
        def requestTypeQuery = sdRequestTypeService.newQueryBuilder().serviceDesk(linkedServiceDesk.id).build()
        sdRequestTypeService.getRequestTypes(loggedUser, requestTypeQuery).getResults().each { type ->
            if (!type.name.equals("Employee exit")) {
                def requestFieldsQuery = sdRequestTypeFieldService.newQueryBuilder().requestType(type.id).serviceDesk(linkedServiceDesk.id).build()
                def requiredFields = [] as ArrayList<RequestTypeField>
                sdRequestTypeFieldService.getCustomerRequestCreateMeta(loggedUser, requestFieldsQuery).requestTypeFields().each { field ->
                    if (field.required()) {
                        requiredFields.add(field)
                    }
                }

                for (int index = 1; index <= 5; index++) {
                    def mappings = [:] as Map<FieldId,FieldInputValue>
                    requiredFields.each { field ->
                        switch (field.fieldId().value()) {
                            case "summary":
                                mappings[field.fieldId()] = FieldInputValue.withValue("JSD Request " + index) // Can't use a GString with variables, it will fails
                                break
                            case "description":
                                mappings[field.fieldId()] = FieldInputValue.withValue("This is a description") // Can't use a GString with variables, it will fails
                                break        
                            default:
                                log.warn field.name()
                                log.warn field.fieldId()
                        }
                    }
                    def requestBuilder = sdRequestService.newCreateBuilder().requestType(type).serviceDesk(linkedServiceDesk).fieldValues(mappings).build()
                    def createdRequest = sdRequestService.createCustomerRequest(loggedUser, requestBuilder)
                }
            }
        }
    }
}

def createSoftwareIssues(ProjectTemplate template, boolean isScrum, Map<String,String> projectInfo, ApplicationUser loggedUser) {
    @JiraAgileBean
    SprintManager sprintManager

    @JiraAgileBean
    SprintIssueService sprintIssueService

    @JiraAgileBean
    RapidViewService rapidViewService

    @JiraAgileBean
    RapidViewManager rapidViewManager

    IssueType epicIssueType 
    IssueType storyIssueType 
    IssueType subTaskIssueType 
    IssueType taskIssueType 
    IssueType bugIssueType 

    def epicLinkField = ComponentAccessor.customFieldManager.getCustomFieldObject(EPIC_LINK_FIELD_ID)
    def epicNameField = ComponentAccessor.customFieldManager.getCustomFieldObject(EPIC_NAME_FIELD_ID)    
    ProjectUtils projectUtils = new ProjectUtils()
    
    def projectData = new Builder().withKey(projectInfo['key']).withLead(loggedUser).withType(ProjectTypeKeys.SOFTWARE).withName(projectInfo['name']).withProjectTemplateKey(template.key.key).build()
    def createOutcome = projectUtils.createProject(loggedUser, projectData, [loggedUser], null)
    def project = createOutcome.get()
    if (project) {
        def issuesForSprint = [] as ArrayList<Issue>
        ComponentAccessor.issueTypeSchemeManager.getIssueTypesForProject(project).each { issueType -> 
            switch (issueType.getName()) { 
                case "Epic": 
                    epicIssueType = issueType 
                    break; 
                case "Task": 
                    taskIssueType = issueType 
                    break; 
                case "Sub-task": 
                    subTaskIssueType = issueType 
                    break; 
                case "Story": 
                    storyIssueType = issueType 
                    break; 
                case "Bug": 
                    bugIssueType = issueType 
                    break;
            }
        }
        
        for (int epicIndex = 1; epicIndex <= 5; epicIndex++) {
            def issueInputParameters = issueService.newIssueInputParameters()
            issueInputParameters
                .setProjectId(project.id) 
                .setSummary("Epic " + epicIndex) 
                .setDescription("Epic " + epicIndex) 
                .setIssueTypeId(epicIssueType.id) 
                .setReporterId(loggedUser.getName())
                .addCustomFieldValue(epicNameField.idAsLong,"Epic " + epicIndex)
            def createValidationResult = issueService.validateCreate(loggedUser, issueInputParameters)
            if (createValidationResult.isValid()) { 
                def createResult = issueService.create(loggedUser, createValidationResult)
                def epicIssue = createResult.issue
                def dayIndex = 1 
                for (int storyIndex = 1; storyIndex <= 10; storyIndex++) { 
                    issueInputParameters = issueService.newIssueInputParameters()
                    def epicLink = epicLinkField
                    issueInputParameters 
                        .setProjectId(project.getId()) 
                        .setSummary("Story " + storyIndex) 
                        .setDescription("Story " + storyIndex) 
                        .setIssueTypeId(storyIssueType.getId())
                        .setReporterId(loggedUser.getName()) 
                        .setAssigneeId(loggedUser.getName())

                    createValidationResult = issueService.validateCreate(loggedUser, issueInputParameters) 
                    if (createValidationResult.isValid()) { 
                        createResult = issueService.create(loggedUser, createValidationResult) 
                        if (!createResult.isValid()) { 
                            log.error("Error while creating the issue.") 
                        } else {
                            def storyIssue = createResult.getIssue()
                            if (storyIndex % 2 == 0) {
                                issuesForSprint.add(storyIssue)
                            }
                            storyIssue.setCustomFieldValue(epicLink, epicIssue) 
                            if (epicLink) { 
                                def changeHolder = new DefaultIssueChangeHolder() 
                                epicLink.updateValue(null, storyIssue, new ModifiedValue(storyIssue.getCustomFieldValue(epicLink), epicIssue),changeHolder) 
                            } 
                            dayIndex = 1 
                            for (int subTaskIndex = 1; subTaskIndex <= 3; subTaskIndex++) { 
                                dayIndex++ 
                                issueInputParameters 
                                    .setProjectId(project.getId()) 
                                    .setSummary("Sub-task " + subTaskIndex) 
                                    .setDescription("Sub-task " + subTaskIndex) 
                                    .setIssueTypeId(subTaskIssueType.getId()) 
                                    .setReporterId(loggedUser.getName()) 
                                    .setDueDate(dayIndex + "/mars/20") 
                                    .setOriginalEstimate("2h") 
                                    .setAssigneeId(loggedUser.getName()) 
                            
                                createValidationResult = issueService.validateSubTaskCreate(loggedUser, storyIssue.getId(), issueInputParameters) 
                                if (createValidationResult.isValid()) { 
                                    createResult = issueService.create(loggedUser, createValidationResult) 
                                    if (!createResult.isValid()) { 
                                        log.error("Error while creating the issue.") 
                                    } else { 
                                        ComponentAccessor.subTaskManager.createSubTaskIssueLink(storyIssue, createResult.getIssue(), loggedUser) 
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
            } else {
                log.warn createValidationResult.errorCollection
            }
        }
        for (int taskIndex = 1; taskIndex <= 5; taskIndex++) { 
            def issueInputParameters = issueService.newIssueInputParameters(); 
            issueInputParameters 
                .setProjectId(project.getId()) 
                .setSummary("Task " + taskIndex) 
                .setDescription("Task " + taskIndex) 
                .setIssueTypeId(taskIssueType.getId()) 
                .setReporterId(loggedUser.getName()) 
                .setDueDate(taskIndex + "/mars/20") 
                .setOriginalEstimate("2h") 
            
            def createValidationResult = issueService.validateCreate(loggedUser, issueInputParameters) 
            if (createValidationResult.isValid()) { 
                def createResult = issueService.create(loggedUser, createValidationResult) 
                if (!createResult.isValid()) { 
                    log.error("Error while creating the issue.") 
                } 
            } else { 
                log.error(createValidationResult.getErrorCollection()) 
            } 
        }

        if (isScrum) {
            def sprintBuilder = Sprint.builder()
            def now = LocalDateTime.now()
            def startDate = now.plusHours(1).toDate().getTime()
            def endDate = now.plusDays(14).toDate().getTime()
            def boardQuery = RapidViewQuery.builder().project(project).build()
            def boardLists = [] as ArrayList<RapidView>
            while (boardLists.size() == 0) {
                Thread.sleep( 5000 )
                rapidViewManager.flushCache() 
                boardLists = rapidViewManager.find(RapidViewSimpleQuery.fromRapidViewQuery(boardQuery)).get()
            }
            def scrumBoard = boardLists.last()
            def newSprint = sprintBuilder
                .name("Sprint for regression testing")
                .rapidViewId(scrumBoard.id)
                .state(Sprint.State.ACTIVE)
                .startDate(startDate)
                .endDate(endDate)
                .build()
            def sprintOutcome = sprintManager.createSprint(newSprint)
            def finalSprint = sprintOutcome.get()

            sprintIssueService.moveIssuesToSprint(loggedUser, finalSprint, issuesForSprint)
        }
    }
}

