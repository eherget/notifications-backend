package com.redhat.cloud.notifications.templates;

import com.redhat.cloud.notifications.models.EmailSubscriptionType;
import io.quarkus.qute.TemplateInstance;

public class EmailTemplateFactory {
    private EmailTemplateFactory() {

    }

    public static AbstractEmailTemplate get(String bundle, String application) {
        if (bundle.toLowerCase().equals("rhel")) {
            switch (application.toLowerCase()) {
                case "policies":
                    return new Policies();
                case "advisor":
                    return new Advisor();
                case "drift":
                    return new Drift();
                default:
                    break;
            }
        } else if (bundle.toLowerCase().equals("openshift")) {
            if (application.toLowerCase().equals("advisor")) {
                return new AdvisorOpenshift();
            }
        }

        return new EmailTemplateNotSupported();
    }
}

class EmailTemplateNotSupported extends AbstractEmailTemplate {
    @Override
    public TemplateInstance getBody(String eventType, EmailSubscriptionType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TemplateInstance getTitle(String eventType, EmailSubscriptionType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSupported(String eventType, EmailSubscriptionType type) {
        return false;
    }
}
