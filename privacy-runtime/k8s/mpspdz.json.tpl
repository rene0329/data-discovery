{
  "apiVersion": "v1",
  "items": [
    {
      "apiVersion": "v1",
      "kind": "PersistentVolumeClaim",
      "metadata": {
        "name": "topic4-mpspdz-state",
        "namespace": "kuscia-a"
      },
      "spec": {
        "accessModes": [
          "ReadWriteOnce"
        ],
        "resources": {
          "requests": {
            "storage": "2Gi"
          }
        },
        "storageClassName": "monitor-storage"
      }
    },
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-a"
      },
      "spec": {
        "ports": [
          {
            "name": "runner",
            "port": 8080,
            "targetPort": 8080
          },
          {
            "name": "mpc",
            "port": 5000,
            "targetPort": 5000
          }
        ],
        "selector": {
          "app": "topic4-mpspdz",
          "topic4.openai.com/party": "A"
        },
        "type": "ClusterIP"
      }
    },
    {
      "apiVersion": "apps/v1",
      "kind": "Deployment",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-a"
      },
      "spec": {
        "replicas": 1,
        "selector": {
          "matchLabels": {
            "app": "topic4-mpspdz",
            "topic4.openai.com/party": "A"
          }
        },
        "strategy": {
          "type": "Recreate"
        },
        "template": {
          "metadata": {
            "labels": {
              "app": "topic4-mpspdz",
              "topic4.openai.com/party": "A"
            }
          },
          "spec": {
            "affinity": {
              "nodeAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": {
                  "nodeSelectorTerms": [
                    {
                      "matchExpressions": [
                        {
                          "key": "kubernetes.io/hostname",
                          "operator": "In",
                          "values": [
                            "__NODE_A__"
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            },
            "containers": [
              {
                "env": [
                  {
                    "name": "TOPIC4_PARTY_ID",
                    "value": "A"
                  },
                  {
                    "name": "TOPIC4_PARTY_INDEX",
                    "value": "0"
                  },
                  {
                    "name": "TOPIC4_AGENT_BASE_URL",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-base-url",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_HOSTS",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-allowed-hosts",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_PATH_PREFIXES",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-path-prefixes",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_IMAGE_DIGEST",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "image-digest",
                        "name": "topic4-privacy-mpspdz-image"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AUTH_TOKEN_FILE",
                    "value": "/run/secrets/topic4-runner/bearer-token"
                  },
                  {
                    "name": "TOPIC4_INPUT_DIR",
                    "value": "/var/run/topic4-inputs"
                  },
                  {
                    "name": "TOPIC4_WORK_DIR",
                    "value": "/var/run/topic4-work"
                  },
                  {
                    "name": "TOPIC4_MPSPDZ_TLS_DIR",
                    "value": "/run/secrets/topic4-mpspdz"
                  }
                ],
                "image": "__MPSPDZ_IMAGE__",
                "imagePullPolicy": "IfNotPresent",
                "livenessProbe": {
                  "failureThreshold": 3,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 20,
                  "timeoutSeconds": 3
                },
                "name": "party",
                "ports": [
                  {
                    "containerPort": 8080,
                    "name": "runner"
                  },
                  {
                    "containerPort": 5000,
                    "name": "mpc"
                  }
                ],
                "readinessProbe": {
                  "failureThreshold": 12,
                  "httpGet": {
                    "path": "/health",
                    "port": "runner"
                  },
                  "periodSeconds": 15,
                  "timeoutSeconds": 20
                },
                "resources": {
                  "limits": {
                    "cpu": "4",
                    "memory": "8Gi"
                  },
                  "requests": {
                    "cpu": "1",
                    "memory": "1Gi"
                  }
                },
                "securityContext": {
                  "allowPrivilegeEscalation": false,
                  "capabilities": {
                    "drop": [
                      "ALL"
                    ]
                  },
                  "readOnlyRootFilesystem": false
                },
                "startupProbe": {
                  "failureThreshold": 60,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 5,
                  "timeoutSeconds": 3
                },
                "volumeMounts": [
                  {
                    "mountPath": "/var/lib/topic4-privacy/jobs",
                    "name": "state"
                  },
                  {
                    "mountPath": "/var/run/topic4-inputs",
                    "name": "inputs"
                  },
                  {
                    "mountPath": "/var/run/topic4-work",
                    "name": "work"
                  },
                  {
                    "mountPath": "/run/secrets/topic4-runner",
                    "name": "auth",
                    "readOnly": true
                  },
                  {
                    "mountPath": "/run/secrets/topic4-mpspdz",
                    "name": "tls",
                    "readOnly": true
                  }
                ]
              }
            ],
            "securityContext": {
              "fsGroup": 10001,
              "runAsNonRoot": true,
              "runAsUser": 10001
            },
            "volumes": [
              {
                "name": "state",
                "persistentVolumeClaim": {
                  "claimName": "topic4-mpspdz-state"
                }
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "inputs"
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "work"
              },
              {
                "name": "auth",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "privacy-mpspdz-runner-auth"
                }
              },
              {
                "name": "tls",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "topic4-mpspdz-tls"
                }
              }
            ]
          }
        }
      }
    },
    {
      "apiVersion": "v1",
      "kind": "PersistentVolumeClaim",
      "metadata": {
        "name": "topic4-mpspdz-state",
        "namespace": "kuscia-b"
      },
      "spec": {
        "accessModes": [
          "ReadWriteOnce"
        ],
        "resources": {
          "requests": {
            "storage": "2Gi"
          }
        },
        "storageClassName": "monitor-storage"
      }
    },
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-b"
      },
      "spec": {
        "ports": [
          {
            "name": "runner",
            "port": 8080,
            "targetPort": 8080
          },
          {
            "name": "mpc",
            "port": 5000,
            "targetPort": 5000
          }
        ],
        "selector": {
          "app": "topic4-mpspdz",
          "topic4.openai.com/party": "B"
        },
        "type": "ClusterIP"
      }
    },
    {
      "apiVersion": "apps/v1",
      "kind": "Deployment",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-b"
      },
      "spec": {
        "replicas": 1,
        "selector": {
          "matchLabels": {
            "app": "topic4-mpspdz",
            "topic4.openai.com/party": "B"
          }
        },
        "strategy": {
          "type": "Recreate"
        },
        "template": {
          "metadata": {
            "labels": {
              "app": "topic4-mpspdz",
              "topic4.openai.com/party": "B"
            }
          },
          "spec": {
            "affinity": {
              "nodeAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": {
                  "nodeSelectorTerms": [
                    {
                      "matchExpressions": [
                        {
                          "key": "kubernetes.io/hostname",
                          "operator": "In",
                          "values": [
                            "__NODE_B__"
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            },
            "containers": [
              {
                "env": [
                  {
                    "name": "TOPIC4_PARTY_ID",
                    "value": "B"
                  },
                  {
                    "name": "TOPIC4_PARTY_INDEX",
                    "value": "1"
                  },
                  {
                    "name": "TOPIC4_AGENT_BASE_URL",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-base-url",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_HOSTS",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-allowed-hosts",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_PATH_PREFIXES",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-path-prefixes",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_IMAGE_DIGEST",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "image-digest",
                        "name": "topic4-privacy-mpspdz-image"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AUTH_TOKEN_FILE",
                    "value": "/run/secrets/topic4-runner/bearer-token"
                  },
                  {
                    "name": "TOPIC4_INPUT_DIR",
                    "value": "/var/run/topic4-inputs"
                  },
                  {
                    "name": "TOPIC4_WORK_DIR",
                    "value": "/var/run/topic4-work"
                  },
                  {
                    "name": "TOPIC4_MPSPDZ_TLS_DIR",
                    "value": "/run/secrets/topic4-mpspdz"
                  }
                ],
                "image": "__MPSPDZ_IMAGE__",
                "imagePullPolicy": "IfNotPresent",
                "livenessProbe": {
                  "failureThreshold": 3,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 20,
                  "timeoutSeconds": 3
                },
                "name": "party",
                "ports": [
                  {
                    "containerPort": 8080,
                    "name": "runner"
                  },
                  {
                    "containerPort": 5000,
                    "name": "mpc"
                  }
                ],
                "readinessProbe": {
                  "failureThreshold": 12,
                  "httpGet": {
                    "path": "/health",
                    "port": "runner"
                  },
                  "periodSeconds": 15,
                  "timeoutSeconds": 20
                },
                "resources": {
                  "limits": {
                    "cpu": "4",
                    "memory": "8Gi"
                  },
                  "requests": {
                    "cpu": "1",
                    "memory": "1Gi"
                  }
                },
                "securityContext": {
                  "allowPrivilegeEscalation": false,
                  "capabilities": {
                    "drop": [
                      "ALL"
                    ]
                  },
                  "readOnlyRootFilesystem": false
                },
                "startupProbe": {
                  "failureThreshold": 60,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 5,
                  "timeoutSeconds": 3
                },
                "volumeMounts": [
                  {
                    "mountPath": "/var/lib/topic4-privacy/jobs",
                    "name": "state"
                  },
                  {
                    "mountPath": "/var/run/topic4-inputs",
                    "name": "inputs"
                  },
                  {
                    "mountPath": "/var/run/topic4-work",
                    "name": "work"
                  },
                  {
                    "mountPath": "/run/secrets/topic4-runner",
                    "name": "auth",
                    "readOnly": true
                  },
                  {
                    "mountPath": "/run/secrets/topic4-mpspdz",
                    "name": "tls",
                    "readOnly": true
                  }
                ]
              }
            ],
            "securityContext": {
              "fsGroup": 10001,
              "runAsNonRoot": true,
              "runAsUser": 10001
            },
            "volumes": [
              {
                "name": "state",
                "persistentVolumeClaim": {
                  "claimName": "topic4-mpspdz-state"
                }
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "inputs"
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "work"
              },
              {
                "name": "auth",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "privacy-mpspdz-runner-auth"
                }
              },
              {
                "name": "tls",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "topic4-mpspdz-tls"
                }
              }
            ]
          }
        }
      }
    },
    {
      "apiVersion": "v1",
      "kind": "PersistentVolumeClaim",
      "metadata": {
        "name": "topic4-mpspdz-state",
        "namespace": "kuscia-c"
      },
      "spec": {
        "accessModes": [
          "ReadWriteOnce"
        ],
        "resources": {
          "requests": {
            "storage": "2Gi"
          }
        },
        "storageClassName": "monitor-storage"
      }
    },
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-c"
      },
      "spec": {
        "ports": [
          {
            "name": "runner",
            "port": 8080,
            "targetPort": 8080
          },
          {
            "name": "mpc",
            "port": 5000,
            "targetPort": 5000
          }
        ],
        "selector": {
          "app": "topic4-mpspdz",
          "topic4.openai.com/party": "C"
        },
        "type": "ClusterIP"
      }
    },
    {
      "apiVersion": "apps/v1",
      "kind": "Deployment",
      "metadata": {
        "name": "topic4-mpspdz",
        "namespace": "kuscia-c"
      },
      "spec": {
        "replicas": 1,
        "selector": {
          "matchLabels": {
            "app": "topic4-mpspdz",
            "topic4.openai.com/party": "C"
          }
        },
        "strategy": {
          "type": "Recreate"
        },
        "template": {
          "metadata": {
            "labels": {
              "app": "topic4-mpspdz",
              "topic4.openai.com/party": "C"
            }
          },
          "spec": {
            "affinity": {
              "nodeAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": {
                  "nodeSelectorTerms": [
                    {
                      "matchExpressions": [
                        {
                          "key": "kubernetes.io/hostname",
                          "operator": "In",
                          "values": [
                            "__NODE_C__"
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            },
            "containers": [
              {
                "env": [
                  {
                    "name": "TOPIC4_PARTY_ID",
                    "value": "C"
                  },
                  {
                    "name": "TOPIC4_PARTY_INDEX",
                    "value": "2"
                  },
                  {
                    "name": "TOPIC4_AGENT_BASE_URL",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-base-url",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_HOSTS",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-allowed-hosts",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AGENT_ALLOWED_PATH_PREFIXES",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "agent-path-prefixes",
                        "name": "topic4-privacy-party-config"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_IMAGE_DIGEST",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "image-digest",
                        "name": "topic4-privacy-mpspdz-image"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AUTH_TOKEN_FILE",
                    "value": "/run/secrets/topic4-runner/bearer-token"
                  },
                  {
                    "name": "TOPIC4_INPUT_DIR",
                    "value": "/var/run/topic4-inputs"
                  },
                  {
                    "name": "TOPIC4_WORK_DIR",
                    "value": "/var/run/topic4-work"
                  },
                  {
                    "name": "TOPIC4_MPSPDZ_TLS_DIR",
                    "value": "/run/secrets/topic4-mpspdz"
                  }
                ],
                "image": "__MPSPDZ_IMAGE__",
                "imagePullPolicy": "IfNotPresent",
                "livenessProbe": {
                  "failureThreshold": 3,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 20,
                  "timeoutSeconds": 3
                },
                "name": "party",
                "ports": [
                  {
                    "containerPort": 8080,
                    "name": "runner"
                  },
                  {
                    "containerPort": 5000,
                    "name": "mpc"
                  }
                ],
                "readinessProbe": {
                  "failureThreshold": 12,
                  "httpGet": {
                    "path": "/health",
                    "port": "runner"
                  },
                  "periodSeconds": 15,
                  "timeoutSeconds": 20
                },
                "resources": {
                  "limits": {
                    "cpu": "4",
                    "memory": "8Gi"
                  },
                  "requests": {
                    "cpu": "1",
                    "memory": "1Gi"
                  }
                },
                "securityContext": {
                  "allowPrivilegeEscalation": false,
                  "capabilities": {
                    "drop": [
                      "ALL"
                    ]
                  },
                  "readOnlyRootFilesystem": false
                },
                "startupProbe": {
                  "failureThreshold": 60,
                  "httpGet": {
                    "path": "/live",
                    "port": "runner"
                  },
                  "periodSeconds": 5,
                  "timeoutSeconds": 3
                },
                "volumeMounts": [
                  {
                    "mountPath": "/var/lib/topic4-privacy/jobs",
                    "name": "state"
                  },
                  {
                    "mountPath": "/var/run/topic4-inputs",
                    "name": "inputs"
                  },
                  {
                    "mountPath": "/var/run/topic4-work",
                    "name": "work"
                  },
                  {
                    "mountPath": "/run/secrets/topic4-runner",
                    "name": "auth",
                    "readOnly": true
                  },
                  {
                    "mountPath": "/run/secrets/topic4-mpspdz",
                    "name": "tls",
                    "readOnly": true
                  }
                ]
              }
            ],
            "securityContext": {
              "fsGroup": 10001,
              "runAsNonRoot": true,
              "runAsUser": 10001
            },
            "volumes": [
              {
                "name": "state",
                "persistentVolumeClaim": {
                  "claimName": "topic4-mpspdz-state"
                }
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "inputs"
              },
              {
                "emptyDir": {
                  "sizeLimit": "1Gi"
                },
                "name": "work"
              },
              {
                "name": "auth",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "privacy-mpspdz-runner-auth"
                }
              },
              {
                "name": "tls",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "topic4-mpspdz-tls"
                }
              }
            ]
          }
        }
      }
    },
    {
      "apiVersion": "v1",
      "kind": "PersistentVolumeClaim",
      "metadata": {
        "name": "topic4-privacy-mpspdz-gateway-state",
        "namespace": "kuscia-master"
      },
      "spec": {
        "accessModes": [
          "ReadWriteOnce"
        ],
        "resources": {
          "requests": {
            "storage": "1Gi"
          }
        },
        "storageClassName": "monitor-storage"
      }
    },
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "name": "topic4-privacy-mpspdz-gateway",
        "namespace": "kuscia-master"
      },
      "spec": {
        "ports": [
          {
            "name": "http",
            "port": 8080,
            "targetPort": 8080
          }
        ],
        "selector": {
          "app": "topic4-privacy-mpspdz-gateway"
        },
        "type": "ClusterIP"
      }
    },
    {
      "apiVersion": "apps/v1",
      "kind": "Deployment",
      "metadata": {
        "name": "topic4-privacy-mpspdz-gateway",
        "namespace": "kuscia-master"
      },
      "spec": {
        "replicas": 1,
        "selector": {
          "matchLabels": {
            "app": "topic4-privacy-mpspdz-gateway"
          }
        },
        "strategy": {
          "type": "Recreate"
        },
        "template": {
          "metadata": {
            "labels": {
              "app": "topic4-privacy-mpspdz-gateway"
            }
          },
          "spec": {
            "affinity": {
              "nodeAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": {
                  "nodeSelectorTerms": [
                    {
                      "matchExpressions": [
                        {
                          "key": "kubernetes.io/hostname",
                          "operator": "NotIn",
                          "values": [
                            "master-88"
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            },
            "containers": [
              {
                "command": [
                  "python3",
                  "/opt/topic4/bin/topic4_privacy_gateway.py"
                ],
                "env": [
                  {
                    "name": "TOPIC4_PROVIDER_ID",
                    "value": "MP_SPDZ"
                  },
                  {
                    "name": "TOPIC4_ENGINE",
                    "value": "MP-SPDZ"
                  },
                  {
                    "name": "TOPIC4_ENGINE_VERSION",
                    "value": "0.4.3"
                  },
                  {
                    "name": "TOPIC4_SOURCE_REVISION",
                    "value": "26a605368e40fed3a7e9cee78c9a3f4390b85eb5"
                  },
                  {
                    "name": "TOPIC4_IMAGE_DIGEST",
                    "valueFrom": {
                      "configMapKeyRef": {
                        "key": "image-digest",
                        "name": "topic4-privacy-mpspdz-image"
                      }
                    }
                  },
                  {
                    "name": "TOPIC4_AUTH_TOKEN_FILE",
                    "value": "/run/secrets/topic4-gateway/bearer-token"
                  },
                  {
                    "name": "TOPIC4_RUNNER_TOKEN_DIR",
                    "value": "/run/secrets/topic4-party-tokens"
                  },
                  {
                    "name": "TOPIC4_PARTY_ENDPOINTS_FILE",
                    "value": "/etc/topic4-privacy/runner-endpoints.json"
                  },
                  {
                    "name": "TOPIC4_GATEWAY_STATE_DIR",
                    "value": "/var/lib/topic4-privacy/gateway"
                  }
                ],
                "image": "__MPSPDZ_IMAGE__",
                "imagePullPolicy": "IfNotPresent",
                "livenessProbe": {
                  "failureThreshold": 3,
                  "httpGet": {
                    "path": "/live",
                    "port": "http"
                  },
                  "periodSeconds": 20,
                  "timeoutSeconds": 3
                },
                "name": "gateway",
                "ports": [
                  {
                    "containerPort": 8080,
                    "name": "http"
                  }
                ],
                "readinessProbe": {
                  "failureThreshold": 12,
                  "httpGet": {
                    "path": "/health",
                    "port": "http"
                  },
                  "periodSeconds": 15,
                  "timeoutSeconds": 25
                },
                "securityContext": {
                  "allowPrivilegeEscalation": false,
                  "capabilities": {
                    "drop": [
                      "ALL"
                    ]
                  }
                },
                "startupProbe": {
                  "failureThreshold": 60,
                  "httpGet": {
                    "path": "/live",
                    "port": "http"
                  },
                  "periodSeconds": 5,
                  "timeoutSeconds": 3
                },
                "volumeMounts": [
                  {
                    "mountPath": "/var/lib/topic4-privacy/gateway",
                    "name": "state"
                  },
                  {
                    "mountPath": "/run/secrets/topic4-gateway",
                    "name": "gateway-auth",
                    "readOnly": true
                  },
                  {
                    "mountPath": "/run/secrets/topic4-party-tokens",
                    "name": "runner-tokens",
                    "readOnly": true
                  },
                  {
                    "mountPath": "/etc/topic4-privacy",
                    "name": "endpoints",
                    "readOnly": true
                  }
                ]
              }
            ],
            "securityContext": {
              "fsGroup": 10001,
              "runAsNonRoot": true,
              "runAsUser": 10001
            },
            "volumes": [
              {
                "name": "state",
                "persistentVolumeClaim": {
                  "claimName": "topic4-privacy-mpspdz-gateway-state"
                }
              },
              {
                "name": "gateway-auth",
                "secret": {
                  "defaultMode": 288,
                  "secretName": "privacy-mpspdz-gateway-auth"
                }
              },
              {
                "name": "runner-tokens",
                "projected": {
                  "defaultMode": 288,
                  "sources": [
                    {
                      "secret": {
                        "items": [
                          {
                            "key": "bearer-token",
                            "path": "A"
                          }
                        ],
                        "name": "privacy-mpspdz-runner-a-auth"
                      }
                    },
                    {
                      "secret": {
                        "items": [
                          {
                            "key": "bearer-token",
                            "path": "B"
                          }
                        ],
                        "name": "privacy-mpspdz-runner-b-auth"
                      }
                    },
                    {
                      "secret": {
                        "items": [
                          {
                            "key": "bearer-token",
                            "path": "C"
                          }
                        ],
                        "name": "privacy-mpspdz-runner-c-auth"
                      }
                    }
                  ]
                }
              },
              {
                "configMap": {
                  "name": "topic4-privacy-mpspdz-gateway-config"
                },
                "name": "endpoints"
              }
            ]
          }
        }
      }
    }
  ],
  "kind": "List"
}
