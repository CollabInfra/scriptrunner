import groovyx.net.http.HTTPBuilder
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.issue.search.SearchProvider
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.bc.issue.search.SearchService
import groovyx.net.http.ContentType
import groovy.json.JsonSlurper
import static com.atlassian.jira.avatar.Avatar.Size
import com.onresolve.scriptrunner.runner.customisers.JiraAgileBean
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.atlassian.greenhopper.service.rapid.view.RapidViewService
import com.atlassian.greenhopper.web.rapid.view.RapidViewHelper
import com.atlassian.greenhopper.service.rapid.view.ColumnService
import com.atlassian.greenhopper.manager.rapidview.RapidViewManager
import com.atlassian.greenhopper.service.sprint.SprintManager
import static com.atlassian.greenhopper.service.sprint.Sprint.State
import static groovyx.net.http.ContentType.JSON
import groovyx.net.http.HttpResponseDecorator
import com.atlassian.jira.util.JiraDurationUtils
import java.text.SimpleDateFormat

@WithPlugin("com.pyxis.greenhopper.jira")

@JiraAgileBean
RapidViewService rapidViewService

@JiraAgileBean
RapidViewHelper rapidViewHelper

@JiraAgileBean
ColumnService columnService

@JiraAgileBean
RapidViewManager rapidViewManager

@JiraAgileBean
SprintManager sprintManager

def PRIVATE-TOKEN = ""

def issueManager = ComponentAccessor.issueManager
def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser)
def searchProvider = ComponentAccessor.getComponent(SearchService)
def cfm = ComponentAccessor.customFieldManager
def jiraDurationUtils = ComponentAccessor.jiraDurationUtils

def cfStoryPoints = cfm.getCustomFieldObjectByName("Story Points")
def cfEpicName = cfm.getCustomFieldObjectByName("Epic Link")
def cfRank = cfm.getCustomFieldObjectByName("Rank")
def cfSprint = cfm.getCustomFieldObjectByName("Sprint")

def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser

def baseurl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")

def sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

def httpclient = new HTTPBuilder("https://gitlab.com");
httpclient.setHeaders(["PRIVATE-TOKEN": "${PRIVATE-TOKEN}"])
httpclient.handler.failure = { resp, data ->
    resp.setData(data)
    String headers = ""
    resp.headers.each {
        headers = headers+"${it.name} : ${it.value}\n"
    }
}
httpclient.handler.success = { resp, reader ->
    [response:resp, reader:reader]
}

def groupId = null

httpclient.get(path: "/api/v4/groups") { resp, reader ->
    reader.each { line ->
        groupId = line.id
    }
}

// This code will delete any existing labels in GitLab. Use it only when testing your migrations on a test group.
httpclient.get(path: "/api/v4/groups/${groupId}/labels") { resp, reader ->
    reader.each { line ->
        httpclient.request(groovyx.net.http.Method.DELETE) {
            uri.path = "/api/v4/groups/${groupId}/labels/${line.id}"
        }
    }
}

// This code will delete any existing projects in GitLab. Use it only when testing your migrations on a test group.
httpclient.get(path: "/api/v4/projects", query: [owned: true]) { resp, reader ->
    reader.each { line ->
        httpclient.request(groovyx.net.http.Method.DELETE) {
            uri.path = "/api/v4/projects/${line.id}"
        }
    }
}

// This code will delete any existing epics in GitLab. Use it only when testing your migrations on a test group.
httpclient.get(path: "/api/v4/groups/${groupId}/epics") { resp, reader ->
    reader.each { line ->
        httpclient.request(groovyx.net.http.Method.DELETE) {
            uri.path = "/api/v4/groups/${groupId}/epics/${line.iid}"
        }
    }
}

// This code will delete any existing milestones in GitLab. Use it only when testing your migrations on a test group.
httpclient.get(path: "/api/v4/groups/${groupId}/milestones") { resp, reader ->
    reader.each { line ->
        httpclient.request(groovyx.net.http.Method.DELETE) {
            uri.path = "/api/v4/groups/${groupId}/milestones/${line.id}"
        }
    }
}

def milestones = []
rapidViewService.getRapidViews(currentUser).get().each { agileBoard ->
    //log.warn agileBoard.savedFilterId
    columnService.getColumnsByStatus(agileBoard).each { k, v ->
        //log.warn k
        // IssueConstantImpl[[GenericEntity:Status][sequence,7][statuscategory,2][name,To Do][iconurl,/][description,][id,10000]]

        //log.warn v
        // com.atlassian.greenhopper.model.rapid.Column@694a20b4[name=gh.workflow.preset.todo,min=<null>,max=<null>,statusIds=[10000],id=4]
    }
    def sprints = sprintManager.getSprintsForView(agileBoard).get()
    def newMilestone = null
    sprints.each { sprint ->
        httpclient.post(
            path: "/api/v4/groups/${groupId}/milestones",
            body: [title: sprint.name, description: sprint.goal, due_date: sprint.endDate, start_date: sprint.startDate]
        ) { resp, milestone ->
            newMilestone = milestone
        }
        if (newMilestone.id && sprint.state.equals(State.CLOSED)) {
            httpclient.request(groovyx.net.http.Method.PUT, JSON) { req ->
                uri.path = "/api/v4/groups/${groupId}/milestones/${newMilestone.id}"
                body = [state_event: "close"]
            }
        }
        milestones.add(newMilestone)
    }
}

ComponentAccessor.projectManager.projects.each { project ->
    //    def url = ComponentAccessor.avatarService.getProjectAvatarURL(project, Size.NORMAL)
    // ajouter avatar dans le body

    def newProjectId = null
    
    httpclient.post(
        path: "/api/v4/projects",
        body: [name: project.name, path: project.key, namespace_id: groupId, description: project.description, issues_access_level: "private", visibility: "private"]
    ) { resp, reader ->
        newProjectId = reader.id
    }

    project.issueTypes.each { issueType ->
        
        def workflow = ComponentAccessor.workflowManager.getWorkflow(project.id, issueType.id)
        workflow.getLinkedStatusObjects().each { status ->
            httpclient.post(
                path: "/api/v4/groups/${groupId}/labels",
                body: [name: "status::${status.name}", color: "blue", description: status.description]
            ) { resp, reader ->
            } 
        }
        
        httpclient.post(
            path: "/api/v4/groups/${groupId}/labels",
            body: [name: "type::${issueType.name}", color: "aquamarine", description: issueType.description]
        ) { resp, reader ->
        }
    }
    

    def newEpics = []
    def query = jqlQueryParser.parseQuery("project = ${project.key} AND issueType = Epic")
    def epics = searchProvider.search(ComponentAccessor.jiraAuthenticationContext.loggedInUser, query, PagerFilter.getUnlimitedFilter())

    epics.getResults().each { issue ->
        httpclient.post(
            path: "/api/v4/groups/${groupId}/epics",
            body: [created_at: issue.created, description: issue.description, due_date_fixed: issue.dueDate, labels: issue.key, title: issue.summary]
        ) { resp, reader ->
            newEpics.add(reader)
        }
    }

    def notEpicquery = jqlQueryParser.parseQuery("project = ${project.key} AND issueType != Epic ORDER BY Rank ASC")
    def issues = searchProvider.search(currentUser, notEpicquery, PagerFilter.getUnlimitedFilter())

    issues.getResults().each { issue ->
        def points = issue.getCustomFieldValue(cfStoryPoints)
        if (!points) { points = (int)0 } else { points = (int)points }
        def epic = issue.getCustomFieldValue(cfEpicName)
        def epicToLink = null
        def newIssue = null
		    def jiraSprint = issue.getCustomFieldValue(cfSprint)
                
        def customFieldsValues = ""
        
     	cfm.getCustomFieldObjects(issue).each { customField ->
            if (!customField.customFieldType.getKey().contains("com.pyxis.greenhopper") && !customField.customFieldType.getKey().contains("jira-development-integration-plugin") && !customField.customFieldType.getKey().contains("com.onresolve.jira") && !customField.customFieldType.getKey().contains("com.atlassian.jira.plugin.system")) {
                log.warn customField.customFieldType.getKey()
            	def value = issue.getCustomFieldValue(customField)
                customFieldsValues = customFieldsValues.concat("${customField.name}: ${value}\n")
            }
        }
        
        def extendedDescription = ""
        if (issue.description) {
           extendedDescription = issue.description.concat(customFieldsValues)
        }
                
        httpclient.post(
            path: "/api/v4/projects/${newProjectId}/issues",
            body: [created_at: issue.created, description: extendedDescription, due_date: issue.dueDate, labels: issue.key, title: issue.summary, weight: points]
        ) { resp, reader ->
            newIssue = reader
        }

        if (issue.originalEstimate) {
            def estimate = jiraDurationUtils.getShortFormattedDuration(issue.originalEstimate)

            httpclient.post(
                path: "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}/time_estimate",
                body: [duration: estimate]
            )  
        }
        
        if (issue.timeSpent) {
            def timeSpent = jiraDurationUtils.getShortFormattedDuration(issue.timeSpent)

            httpclient.post(
                path: "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}/add_spent_time",
                body: [duration: timeSpent]
            )                 
        }
  
        if (epic && newIssue) {
            epicToLink = newEpics.find { it.labels.contains(epic.key) }
            httpclient.post(
                path: "/api/v4/groups/${groupId}/epics/${epicToLink.iid}/issues/${newIssue.id}",
                body: [created_at: issue.created, description: issue.description, due_date: issue.dueDate, labels: issue.key, title: issue.summary, weight: points]
            ) 
        }
        
        if (jiraSprint) {
            def sprintName = null
            jiraSprint.each { sprint ->
                sprintName = sprint.name
            }
            
            def foundSprint = milestones.find { it.title.equals(sprintName) }
            if (foundSprint) {
                httpclient.request(groovyx.net.http.Method.PUT, JSON) { req ->
                    uri.path = "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}"
                    body = [milestone_id: foundSprint.id]
                }
            }
        }
        
        /*
        Can't set updated_at, documentation is wrong
        if (issue.updated) {
            log.warn "issue.updated"
            def updatedDate = sdf.format(new Date(issue.updated.getTime()))
            log.warn "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}"
            httpclient.request(groovyx.net.http.Method.PUT, JSON) { req ->
                uri.path = "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}"
                body = [updated_at: updatedDate]
            }
        }
        */
        
        if (issue.resolution) {
            //def updatedDate = sdf.format(new Date(issue.resolutionDate.getTime()))
            httpclient.request(groovyx.net.http.Method.PUT, JSON) { req ->
                uri.path = "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}"
                body = [state_event: "close"]
            }
        } else {
            httpclient.request(groovyx.net.http.Method.PUT, JSON) { req ->
                uri.path = "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}"
                body = [add_labels: "status::${issue.status.name}"]
            }
        }
        
        ComponentAccessor.commentManager.getComments(issue).each { jiraComment ->
            httpclient.post(
                path: "/api/v4/projects/${newProjectId}/issues/${newIssue.iid}/discussions",
                body: [created_at: jiraComment.created, body: jiraComment.body]
            ) { resp, reader ->
                //log.warn reader
            }            
        }
    }

    if (project.isArchived()) {
        // POST /projects/:id/archive
    }
}


