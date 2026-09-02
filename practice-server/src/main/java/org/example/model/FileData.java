package org.example.model;

import java.io.Serializable;
import java.util.Objects;

// FileData 实体类用于封装从 DaemonSet API 返回的文件元数据
public class FileData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;           // 文件名 (e.g., "Yelp.csv")
    private String path;           // 文件在宿主机 /data 目录下的完整路径 (e.g., "/data/Yelp.csv")
    private long sizeBytes;        // 文件大小 (字节)
    private long lastModified;     // 最后修改时间 (Unix 毫秒时间戳)
    private String fileType;       // 文件类型推断 (e.g., "CSV", "PARQUET", "UNKNOWN")
    private String md5Hash;        // 文件内容的MD5哈希 (可选，当前已决定不计算，但可预留字段)
    private String metadataJson;   // 同名 *.meta.json 的原始内容

    // --- 构造函数 (可选，方便创建对象) ---
    public FileData() {}

    public FileData(String name, String path, long sizeBytes, long lastModified, String fileType, String md5Hash) {
        this.name = name;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.lastModified = lastModified;
        this.fileType = fileType;
        this.md5Hash = md5Hash;
    }

    // --- Getters and Setters ---
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getMd5Hash() { return md5Hash; }
    public void setMd5Hash(String md5Hash) { this.md5Hash = md5Hash; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }


    // --- toString, equals, hashCode (推荐添加，方便调试和集合操作) ---
    @Override
    public String toString() {
        return "FileData{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", sizeBytes=" + sizeBytes +
                ", lastModified=" + lastModified +
                ", fileType='" + fileType + '\'' +
                ", md5Hash='" + md5Hash + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileData fileData = (FileData) o;
        // equals 的实现应基于能唯一标识文件的属性
        // path 和 name 是关键
        return sizeBytes == fileData.sizeBytes &&
                lastModified == fileData.lastModified &&
                Objects.equals(name, fileData.name) &&
                Objects.equals(path, fileData.path) &&
                Objects.equals(fileType, fileData.fileType) &&
                Objects.equals(md5Hash, fileData.md5Hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path, sizeBytes, lastModified, fileType, md5Hash);
    }
}
