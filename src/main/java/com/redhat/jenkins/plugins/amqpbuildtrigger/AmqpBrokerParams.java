package com.redhat.jenkins.plugins.amqpbuildtrigger;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;

import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.lang3.StringUtils;
import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsConnectionFactory;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.security.ACL;

// Temporary, until enforcer issues with org.apache.commons.validator can be sorted out
import com.redhat.jenkins.plugins.validator.UrlValidator;
import java.util.Collections;

public class AmqpBrokerParams implements Describable<AmqpBrokerParams> {
    private static final String DISPLAY_NAME = "AMQP server parameters";

    private String url;
    private String user;
    private String credentialsId;
    private String sourceAddr;

    @DataBoundConstructor
    public AmqpBrokerParams(String url, String username, String credentialsId, String sourceAddr) {
        this.url = url;
        this.user = username;
        this.credentialsId = credentialsId;
        this.sourceAddr = sourceAddr;
    }

    public Secret getPassword() {
        return getPassword(credentialsId);
    }

    public static Secret getPassword(String credentialsId) {
        UsernamePasswordCredentials c = getCredentials(credentialsId);
        return c != null ? c.getPassword() : Secret.fromString("");
    }
    
    public static UsernamePasswordCredentials getCredentials(String credentialsId) {
        return CredentialsProvider.lookupCredentials(
            UsernamePasswordCredentials.class,
            Jenkins.getActiveInstance(),
            ACL.SYSTEM,
            Collections.emptyList()
        ).stream().filter(c -> c.getId().equals(credentialsId)).findFirst().orElse(null);
    }

    public String getUrl() {
        return StringUtils.strip(StringUtils.stripToNull(url), "/");
    }

    public String getUser() {
        return user;
    }

    public String getSourceAddr() {
        return sourceAddr;
    }

    @DataBoundSetter
    public void setUrl(String url) {
        this.url = url;
    }

    @DataBoundSetter
    public void setUser(String user) {
        this.user = user;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    // public void setUserPassword(String password) {
    //     this.password = Secret.fromString(password);
    // }

    @DataBoundSetter
    public void setSourceAddr(String sourceAddr) {
        this.sourceAddr = sourceAddr;
    }

    public String toString() {
        return url + "/" + sourceAddr;
    }

    public boolean isValid() {
        if (url != null && !url.isEmpty()) {
            UrlValidator urlValidator = new UrlValidator();
            if (!urlValidator.isValid(getUrl()))
                return false;
        }
        return sourceAddr != null && !sourceAddr.isEmpty();
    }

    @Override
    public Descriptor<AmqpBrokerParams> getDescriptor() {
        return Jenkins.getInstance().getDescriptorByType(AmqpBrokerUrlDescriptor.class);
    }

    @Extension
    public static class AmqpBrokerUrlDescriptor extends Descriptor<AmqpBrokerParams> {

        @Override
        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        public static ExtensionList<AmqpBrokerUrlDescriptor> all() {
            return Jenkins.getInstance().getExtensionList(AmqpBrokerUrlDescriptor.class);
        }

        @POST
        public FormValidation doTestConnection(@QueryParameter("url") String url,
        		                               @QueryParameter("user") String user,
        		                               @QueryParameter("credentialsId") String credentialsId,
        		                               @QueryParameter("sourceAddr") String sourceAddr) throws ServletException {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            String uri = StringUtils.strip(StringUtils.stripToNull(url), "/");
            UrlValidator urlValidator = new UrlValidator();
            if (uri != null && urlValidator.isValid(uri)) {
                try {
                    JmsConnectionFactory factory = new JmsConnectionFactory(uri);
                    JmsConnection connection;
                    Secret spw = getPassword(credentialsId);
                    if (user.isEmpty() || spw.getPlainText().isEmpty()) {
                        connection = (JmsConnection)factory.createConnection();
                    } else {
                        connection = (JmsConnection)factory.createConnection(user, spw.getPlainText());
                    }
                    connection.setExceptionListener(new MyExceptionListener());
                    connection.start();

                    Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    Queue queue = session.createQueue(sourceAddr);
                    MessageConsumer messageConsumer = session.createConsumer(queue);

                    messageConsumer.close();
                    session.close();
                    connection.close();
                    return FormValidation.ok("OK");
                } catch (javax.jms.JMSException e) {
                    return FormValidation.error(e.toString());
                }
            }
            return FormValidation.error("Invalid server URL");
        }
    }

    private static class MyExceptionListener implements ExceptionListener {
        @Override
        public void onException(JMSException exception) {
            System.out.println("Connection ExceptionListener fired, exiting.");
            exception.printStackTrace(System.out);
        }
    }
}
