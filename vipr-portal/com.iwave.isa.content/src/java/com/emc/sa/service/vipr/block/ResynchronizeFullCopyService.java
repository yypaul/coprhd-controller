/*
 * Copyright 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block;

import static com.emc.sa.service.ServiceParams.COPIES;
import static com.emc.sa.service.ServiceParams.VOLUME;

import java.net.URI;
import java.util.List;

import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.vipr.ViPRService;

@Service("ResynchronizeFullCopy")
public class ResynchronizeFullCopyService extends ViPRService {

    @Param(value = VOLUME, required = false)
    protected URI consistencyGroupId;

    @Param(COPIES)
    protected List<String> copyIds;

    @Override
    public void execute() throws Exception {
        if (consistencyGroupId != null && !"NONE".equals(consistencyGroupId.toString())) {
            for (URI copyId : uris(copyIds)) {
                ConsistencyUtils.resynchronizeFullCopy(consistencyGroupId, copyId);
            }
        } else {
            BlockStorageUtils.resynchronizeFullCopies(uris(copyIds));
        }
    }

}
