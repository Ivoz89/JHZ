package net.amg.jira.plugins.rest.history;

import com.atlassian.jira.issue.status.Status;

import javax.annotation.concurrent.Immutable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Encapsulates attributes of a JIRA status.
 * Created by Ivo on 01/05/15.
 */
@Immutable
@XmlRootElement
public class StatusRepresentation {

    @XmlElement
    private String name;

    /**
     * Extracts required attributes from the given JIRA status.
     *
     * @param status
     */
    public StatusRepresentation(Status status) {
        this.name = status.getName();
    }

}
