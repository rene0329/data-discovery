package org.example.dto.registration;

import java.util.List;

public class RegisterRuntimeImageRequest {
    private String name;
    private String imageRef;
    private String taskType;
    private String modelType;
    private List<String> command;
    private List<String> argsTemplate;
    private String dataPathTemplate;
    private ResourceRequirements defaultResources;
    private String pullSecretRef;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImageRef() { return imageRef; }
    public void setImageRef(String imageRef) { this.imageRef = imageRef; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }
    public List<String> getArgsTemplate() { return argsTemplate; }
    public void setArgsTemplate(List<String> argsTemplate) { this.argsTemplate = argsTemplate; }
    public String getDataPathTemplate() { return dataPathTemplate; }
    public void setDataPathTemplate(String dataPathTemplate) { this.dataPathTemplate = dataPathTemplate; }
    public ResourceRequirements getDefaultResources() { return defaultResources; }
    public void setDefaultResources(ResourceRequirements defaultResources) { this.defaultResources = defaultResources; }
    public String getPullSecretRef() { return pullSecretRef; }
    public void setPullSecretRef(String pullSecretRef) { this.pullSecretRef = pullSecretRef; }
}
