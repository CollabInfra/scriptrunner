import org.radeox.util.StringBufferWriter
import com.atlassian.jira.component.ComponentAccessor
import org.apache.commons.lang3.StringEscapeUtils
import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ResponseHandler
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import com.atlassian.applinks.api.CredentialsRequiredException
import groovy.sql.Sql
import org.ofbiz.core.entity.ConnectionFactory
import org.ofbiz.core.entity.DelegatorInterface
import java.sql.Connection
import com.atlassian.jira.util.Visitor
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.jira.issue.search.SearchRequestEntity
import com.atlassian.jira.issue.search.SearchRequestManager

def ApplicationLink getPrimaryConfluenceLink() {
    def applicationLinkService = ComponentLocator.getComponent(ApplicationLinkService.class)
    final ApplicationLink conflLink = applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType.class)
    conflLink
}

def confluenceLink = getPrimaryConfluenceLink()
assert confluenceLink

def delegator = (DelegatorInterface) ComponentAccessor.getComponent(DelegatorInterface)
String helperName = delegator.getGroupHelperName("default")

Connection conn = ConnectionFactory.getConnection(helperName)
Sql sql = new Sql(conn)

def customFieldManager = ComponentAccessor.getCustomFieldManager()
def workflowManager = ComponentAccessor.getWorkflowManager()
def searchRequestManager = ComponentAccessor.getComponent(SearchRequestManager.class);

def allWorkflows = workflowManager.workflows

def allFilters = [] as ArrayList<SearchRequestEntity>

    searchRequestManager.visitAll(new Visitor<SearchRequestEntity>() {
        @Override
        public void visit(SearchRequestEntity searchRequestEntity) {
            allFilters.add(searchRequestEntity)
        }
    });

def pageContent = new StringBuffer()

pageContent.append("<table>")

pageContent.append("<tr>") 
pageContent.append("<th>" << "Field name and description" << "</th>")
pageContent.append("<th>" << "Untranslated field name and description" << "</th>")
pageContent.append("<th>" << "Is field global?" << "</th>")
pageContent.append("<th>" << "Context - projects" << "</th>")
pageContent.append("<th>" << "Context - issue types" << "</th>")
pageContent.append("<th>" << "References in workflows" << "</th>")
pageContent.append("<th>" << "Number of issues with values" << "</th>")
pageContent.append("<th>" << "References in filters" << "</th>")
pageContent.append("</tr>") 

customFieldManager.getCustomFieldObjects().each { customField ->
    def shortFieldName = "cf[" + customField.getIdAsLong() + "]"
    
    pageContent.append("<tr>") 
    
	pageContent.append("<td>" << StringEscapeUtils.escapeHtml4(customField.fieldName) << "<br/>" << StringEscapeUtils.escapeHtml4(customField.description) << "</td>")
    pageContent.append("<td>" << StringEscapeUtils.escapeHtml4(customField.untranslatedName) << "<br/>" << StringEscapeUtils.escapeHtml4(customField.untranslatedDescription) << "</td>")
    pageContent.append("<td>" << customField.global << "</td>")
    
    pageContent.append("<td>")
    customField.associatedProjectObjects.each { project ->
         pageContent.append(StringEscapeUtils.escapeHtml4(project.name) << "<br/>")
    }
    pageContent.append("</td>")

	pageContent.append("<td>")
    customField.associatedIssueTypes.each { issueType ->
        if (issueType != null)
			pageContent.append(StringEscapeUtils.escapeHtml4(issueType.name) << "<br/>")
    }
    pageContent.append("</td>")
    
    pageContent.append("<td>")
    allWorkflows.each { workflow ->
        workflow.allActions.each { action ->
            workflow.getPostFunctionsForTransition(action).each { postFunction ->
                def fieldName = postFunction.getArgs().get("field.name")
                if (fieldName != null) {
                    if (fieldName.equals(shortFieldName)) {
                        pageContent.append(StringEscapeUtils.escapeHtml4(workflow.displayName))
                        if (!workflow.active) {
                            pageContent.append(" (inactive workflow)")
                        }
                        pageContent.append("<br/>")
                    }
                }
                
            }
        }
    }
    pageContent.append("</td>")
    
    pageContent.append("<td>")
    def sqlStmt = "SELECT COUNT(cv.issue) AS totalIssues FROM customfield cf JOIN customfieldvalue cv on cf.ID = cv.customfield WHERE cf.id = " + customField.getIdAsLong() + " GROUP BY cf.cfname"
    sql.eachRow(sqlStmt) {
        pageContent.append("${it.totalIssues}")
    }
    pageContent.append("</td>")    
    
    
   pageContent.append("<td>")
    allFilters.each { filter ->
        if (filter.request.contains(shortFieldName) || filter.request.contains(customField.fieldName)) {
            pageContent.append(filter.name)
        }
    }
    pageContent.append("</td>")    
    
    pageContent.append("</tr>")
}

pageContent.append("</table>")

def params = [
    type : "page",
    title: "Custom fields usage",
    space: 
        key: "DEMO"
    ],
    body : [
        storage: [
            value         : pageContent,
            representation: "storage"
        ],
    ],
]


def authenticatedRequestFactory = confluenceLink.createAuthenticatedRequestFactory()

try {
  authenticatedRequestFactory
    .createRequest(Request.MethodType.POST, "rest/api/content")
    .addHeader("Content-Type", "application/json")
    .setRequestBody(new JsonBuilder(params).toString())
    .execute(new ResponseHandler<Response>() {
        @Override
        void handle(Response response) throws ResponseException {
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                throw new Exception(response.getResponseBodyAsString())
            } else {
                def webUrl = new JsonSlurper().parseText(response.responseBodyAsString)["_links"]["webui"]
            }
        }
    })
}
catch (CredentialsRequiredException e) {
    log.warn("Your permission is required to make this request, go to: ${e.authorisationURI}")
    return
}
