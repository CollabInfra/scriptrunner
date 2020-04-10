# scriptrunner
Groovy scripts and code snippets for Adaptavist's ScriptRunner for Jira

ListCustomFieldsUsage.groovy is a script that will find all custom fields in your Jira Server (or Data Center) instance, display the translated and untranslated description, if the field is global (i.e. the field don't have contexts), the name of projects and issues types related to contexts (if any), if the field is referenced into workflows and the number of issues that use the custom field.

The result will be uploaded to Confluence, and the script need a Confluence application link in your Jira instance. You need to put the space key and the page title in the params variable near the end of the script.
