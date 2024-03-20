package com.redhat.jenkins.plugins.amqpbuildtrigger;

import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.logging.Logger;

public class AmqpBuildTrigger<T extends Job<?, ?> & ParameterizedJobMixIn.ParameterizedJob> extends Trigger<T> {
    private static final Logger LOGGER = Logger.getLogger(AmqpBuildTrigger.class.getName());
    private List<AmqpBrokerParams> amqpBrokerParamsList = new CopyOnWriteArrayList<AmqpBrokerParams>();
    private static final String others_param_name = "OTHERS";

    @DataBoundConstructor
    public AmqpBuildTrigger(List<AmqpBrokerParams> amqpBrokerParamsList) {
        super();
        this.amqpBrokerParamsList = amqpBrokerParamsList;
    }

    public List<AmqpBrokerParams> getAmqpBrokerParamsList() {
        return amqpBrokerParamsList;
    }

    @DataBoundSetter
    public void setAmqpBrokerParamsList(List<AmqpBrokerParams> amqpBrokerParamsList) {
        this.amqpBrokerParamsList = amqpBrokerParamsList;
    }

    @Override
    public String toString() {
        return getProjectName();
    }

    public String getProjectName() {
        if(job != null){
            return job.getFullName();
        }
        return "";
    }

    public void scheduleBuild(String messageSource, String message) {
        /* Launch the build job, if the AMQP message payload is in JSON format, use
        /  the parameters from the payload, if not, call the build without parameters
        */
        if (job != null && messageSource != null) {
            LOGGER.info("ScheduleBuild with message: " + message);
            JSONObject jMessage = null;
            try {
                jMessage = new JSONObject().fromObject(message);
            } catch (Exception e) {
                LOGGER.info("Message '" + message + "' NOT in JSON format");
            }
            if (jMessage != null) {
                LOGGER.info("Message in JSON format, converting to matching params ...");
                // get the parameters from the message payload
                List<ParameterValue> parameters = getParamsFromMsgPayload(jMessage, job);
                // call the build with the parameters
                ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(new RemoteBuildCause(messageSource)), new ParametersAction(parameters));
            } else {
                LOGGER.info("Triggering job without parameters ...");
                ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(new RemoteBuildCause(messageSource)));
            }
        }
    }

    private List<ParameterValue> getParamsFromMsgPayload(JSONObject payload, Job<?, ?> job) {
        /* For every parameter in the payload, look for a matching parameter in the job
        /  and create a list of ParameterValue objects to be used in the build
        */
        List<ParameterValue> parameters = new CopyOnWriteArrayList<ParameterValue>();
        JSONObject otherParams = new JSONObject();
        if (job != null) {
            for (Object key : payload.keySet()) {
                if (key instanceof String) {
                    String paramName = (String) key;
                    // Look for a matching name parameter in the job
                    ParameterDefinition paramDef = findParameterInJob(paramName, job);
                    if (paramDef != null) {
                        // get the param value from the payload
                        ParameterValue paramValue = getParamValueFromPayload(
                            payload, paramName, 
                            paramDef
                        );
                        if (paramValue != null) {
                            parameters.add(paramValue);
                        }
                    } else {
                        // this param at payload is not in the job, so add it to the list of "other parameters"
                        otherParams.put(paramName, payload.get(paramName));
                    }
                }
            }

            // if otherParams is not empty, add it to the job as a single string parameter
            if (!otherParams.isEmpty()) {
                parameters.add(new StringParameterValue(others_param_name, otherParams.toString()));
            }
        }
        LOGGER.info("Params: " + parameters.toString());
        return parameters;
    }

    private ParameterDefinition findParameterInJob(String parameterName, Job<?, ?> job) {
        ParametersDefinitionProperty properties = job.getProperty(ParametersDefinitionProperty.class);
        if (properties != null) {
            for (ParameterDefinition paramDef : properties.getParameterDefinitions()) {
                if (paramDef.getName().equals(parameterName)) {
                    return paramDef;
                }
            }
        }
        return null;
    }

    private ParameterValue getParamValueFromPayload(JSONObject msgParams, String paramName, ParameterDefinition paramDef) {
        LOGGER.info("Searching for param: " + paramName);
        ParameterValue newParam = null;

        if (msgParams != null && paramName != null && !paramName.isEmpty() && paramDef != null) {          
            if (paramDef instanceof BooleanParameterDefinition) {
                Boolean defaultValue = ((BooleanParameterDefinition) paramDef).getDefaultParameterValue().getValue();
                if (defaultValue != null) {
                    Boolean jsonParamValue = msgParams.optBoolean(paramName, defaultValue);
                    newParam = new BooleanParameterValue(
                        paramName, 
                        jsonParamValue
                    );
                }
            } else if (paramDef instanceof ChoiceParameterDefinition) {
                StringParameterValue choiceParamVal = ((ChoiceParameterDefinition) paramDef).getDefaultParameterValue();
                if (choiceParamVal != null) {
                    String defaultValue = choiceParamVal.getValue();
                    String jsonParamValue = msgParams.optString(paramName, defaultValue);
                    newParam = new StringParameterValue(
                        paramName,
                        jsonParamValue
                    );
                }
            } else if (paramDef instanceof StringParameterDefinition) {
                String defaultValue = ((StringParameterDefinition) paramDef).getDefaultValue();
                String jsonParamValue = msgParams.optString(paramName, defaultValue);
                newParam = new StringParameterValue(
                    paramName, 
                    jsonParamValue
                );
            }
        }
        
        return newParam;
    }

    @Override
    public AmqpBuildTriggerDescriptor getDescriptor() {
        return (AmqpBuildTriggerDescriptor) Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static class AmqpBuildTriggerDescriptor extends TriggerDescriptor {

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "AMQP-1.0 trigger";
        }
    }
}
