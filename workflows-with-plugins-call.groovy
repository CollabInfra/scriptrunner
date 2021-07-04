import com.atlassian.jira.component.ComponentAccessor

def sb = new StringBuilder()

ComponentAccessor.workflowManager.activeWorkflows.each { workflow ->
    def postFunctions = ComponentAccessor.workflowManager.getPostFunctionsForWorkflow(workflow)
    sb << "Workflow: " << workflow.name << "<br/>"
    workflow.allActions.each { action ->
        workflow.getPostFunctionsForTransition(action).each { postFunction ->
            def className = postFunction.getArgs().get("class.name") as String
            if (!className.contains("com.atlassian.jira")) {
                sb << "&emsp;Step: " << action.name << "&emsp;Class: " << className << "<br/>"
                log.warn action.name
                log.warn className
            }
        }
        action.validators.each { validator ->
            def className = validator.getArgs().get("class.name") as String
            if (!className.contains("com.atlassian.jira")) {
                sb << "&emsp;Step: " << action.name << "&emsp;Class: " << className << "<br/>"
            }
        }
    }
    sb << "<br/>"
}


return sb.toString()