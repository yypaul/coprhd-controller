/*
 * Copyright 2016 Dell Inc. or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.emc.storageos.model.orchestration;

import java.util.Map;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import com.emc.storageos.model.DataObjectRestRep;
import com.emc.storageos.model.RestLinkRep;

@XmlRootElement(name = "primitive")
public class PrimitiveRestRep extends DataObjectRestRep {
    
    private String type;
    private String friendlyName;
    private String description;
    private String successCriteria;
    private  Map<String, String> attributes;
    private RestLinkRep resource;

    private Map<String,InputParameterRestRep> input;
    private Map<String,OutputParameterRestRep> output;

    @XmlElement(name = "type")
    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }
    
    @XmlElement(name = "friendly_name")
    public String getFriendlyName() {
        return friendlyName;
    }

    public void setFriendlyName(final String friendlyName) {
        this.friendlyName = friendlyName;
    }

    @XmlElement(name = "description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @XmlElement(name = "success_criteria")
    public String getSuccessCriteria() {
        return successCriteria;
    }

    public void setSuccessCriteria(final String successCriteria) {
        this.successCriteria = successCriteria;
    }

    @XmlElement(name = "attributes")
    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(final  Map<String, String> attributes) {
        this.attributes = attributes;
    }

    @XmlElement(name = "input")
    public Map<String,InputParameterRestRep> getInput() {
        return input;
    }

    public void setInput(final Map<String,InputParameterRestRep> input) {
        this.input = input;
    }

    @XmlElement(name = "output")
    public Map<String,OutputParameterRestRep> getOutput() {
        return output;
    }

    public void setOutput(final Map<String,OutputParameterRestRep> output) {
        this.output = output;
    }

    @XmlElement(name = "resource")
    public RestLinkRep getResource() {
        return resource;
    }

    public void setResource(RestLinkRep resource) {
        this.resource = resource;
    }
}
