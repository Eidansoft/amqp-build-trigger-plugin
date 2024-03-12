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
import hudson.model.BooleanParameterValue;
import hudson.model.BooleanParameterDefinition;
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
        if (job != null && messageSource != null) {
            LOGGER.info("ScheduleBuild with message: " + message);
            JSONObject jMessage = new JSONObject().fromObject(message);
            if (jMessage != null) {
                LOGGER.info("Message in JSON format, converting to matching params ...");
                // get the parameters from the message payload
                List<ParameterValue> parameters = getParamsFromMsgPayload(jMessage, job);
                // call the build with the parameters
                ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(new RemoteBuildCause(messageSource)), new ParametersAction(parameters));
            } else {
                LOGGER.info("Message NOT in JSONArray format");
                ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(new RemoteBuildCause(messageSource)));
            }
        }
    }

    private List<ParameterValue> getParamsFromMsgPayload(JSONObject payload, Job<?, ?> project) {
        List<ParameterValue> parameters = new CopyOnWriteArrayList<ParameterValue>();
        if (project != null) {
            ParametersDefinitionProperty properties = project.getProperty(ParametersDefinitionProperty.class);
            if (properties != null) {
                for (ParameterDefinition paramDef : properties.getParameterDefinitions()) {
                    ParameterValue defaultParam = paramDef.getDefaultParameterValue();
                    if (defaultParam != null) {
                        ParameterValue payloadParam = getParamValueFromPayload(
                            payload, defaultParam.getName().toUpperCase(),
                            paramDef instanceof BooleanParameterDefinition ? "bool" : "string",
                            defaultParam
                        );
                        if (payloadParam != null) {
                            parameters.add(payloadParam);
                        }
                    }
                }
            }
        }
        LOGGER.info("Params: " + parameters.toString());
        return parameters;
    }

    private ParameterValue getParamValueFromPayload(JSONObject msgParams, String paramName, String paramType, ParameterValue defaultParam) {
        LOGGER.info("Searching for param: " + paramName);
        ParameterValue newParam = null;

        if (msgParams != null && defaultParam != null && paramName != null && paramType != null && !paramName.isEmpty() && !paramType.isEmpty()){
            Object defaultValue = defaultParam.getValue();
            if (defaultValue != null) {
                if (paramType.equals("string")) {
                    String jsonParamValue = msgParams.optString(paramName, defaultValue.toString());
                    newParam = new StringParameterValue(
                        paramName, 
                        jsonParamValue
                    );
                } else if (paramType.equals("bool")) {
                    Boolean jsonParamValue = msgParams.optBoolean(paramName, (Boolean) defaultValue);
                    newParam = new BooleanParameterValue(
                        paramName, 
                        jsonParamValue
                    );
                }
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
