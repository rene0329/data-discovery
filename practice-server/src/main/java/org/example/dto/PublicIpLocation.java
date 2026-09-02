package org.example.dto;

import lombok.Value;

/** IP is returned with the result so clients never attach it to a different egress IP. */
@Value
public class PublicIpLocation {
    String ip;
    String displayName;
    String status;
    String source;
}
