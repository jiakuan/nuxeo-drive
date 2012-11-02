/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.service.impl;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * Summary of document changes, including:
 * <ul>
 * <li>A list of document changes</li>
 * <li>A map of document models that have changed</li>
 * <li>A status code</li>
 * </ul>
 * A document change is implemented by {@link DocumentChange}.
 *
 * @author Antoine Taillefer
 */
public class DocumentChangeSummary implements Serializable {

    private static final long serialVersionUID = -5719579884697229867L;

    public static final String STATUS_FOUND_CHANGES = "found_changes";

    public static final String STATUS_TOO_MANY_CHANGES = "too_many_changes";

    public static final String STATUS_NO_CHANGES = "no_changes";

    protected List<DocumentChange> documentChanges;

    // Ignored from JSON serialization for the moment as we need to define
    // exactly which fields from the DocumentModel are needed by the client
    // device
    @JsonIgnore
    protected Map<String, DocumentModel> changedDocModels;

    protected String statusCode;

    protected Long syncDate;

    public DocumentChangeSummary() {
        // Needed for JSON deserialization
    }

    public DocumentChangeSummary(List<DocumentChange> documentChanges,
            Map<String, DocumentModel> changedDocModels, String statusCode,
            Long syncDate) {
        this.documentChanges = documentChanges;
        this.changedDocModels = changedDocModels;
        this.statusCode = statusCode;
        this.syncDate = syncDate;
    }

    public List<DocumentChange> getDocumentChanges() {
        return documentChanges;
    }

    public void setDocumentChanges(List<DocumentChange> documentChanges) {
        this.documentChanges = documentChanges;
    }

    @JsonIgnore
    public Map<String, DocumentModel> getChangedDocModels() {
        return changedDocModels;
    }

    @JsonIgnore
    public void setChangedDocModels(Map<String, DocumentModel> changedDocModels) {
        this.changedDocModels = changedDocModels;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public Long getSyncDate() {
        return syncDate;
    }

    public void setSyncDate(Long syncDate) {
        this.syncDate = syncDate;
    }

}