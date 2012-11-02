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
package org.nuxeo.drive.operations;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;

import org.codehaus.jackson.map.ObjectMapper;
import org.nuxeo.drive.service.NuxeoDriveManager;
import org.nuxeo.drive.service.impl.DocumentChangeSummary;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.impl.blob.InputStreamBlob;
import org.nuxeo.runtime.api.Framework;

/**
 * Gets a summary of document changes in the synchronization roots of the
 * currently authenticated user.
 *
 * @author Antoine Taillefer
 */
@Operation(id = NuxeoDriveGetDocumentChangeSummary.ID, category = Constants.CAT_SERVICES, label = "Nuxeo Drive: Get document change summary")
public class NuxeoDriveGetDocumentChangeSummary {

    public static final String ID = "NuxeoDrive.GetDocumentChangeSummary";

    @Context
    protected CoreSession session;

    @Param(name = "lastSuccessfulSync")
    protected Long lastSuccessfulSync;

    @OperationMethod
    public Blob run() throws Exception {

        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        DocumentChangeSummary docChangeSummary = driveManager.getDocumentChangeSummary(
                session.getPrincipal().getName(), session, lastSuccessfulSync);

        ObjectMapper mapper = new ObjectMapper();
        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, docChangeSummary);
        return new InputStreamBlob(new ByteArrayInputStream(
                writer.toString().getBytes("UTF-8")), "application/json");
    }

}