package org.example.comparison.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteFetchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteFetchService.class);

    private final String remoteServer;

    private final String remoteDataPath;

    private final String localDataPath;

    public RemoteFetchService(String remoteServer, String remoteDataPath, String localDataPath) {
        this.remoteServer = remoteServer;
        this.remoteDataPath = remoteDataPath;
        this.localDataPath = localDataPath;
    }
}
