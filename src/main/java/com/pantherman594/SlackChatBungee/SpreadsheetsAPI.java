package com.pantherman594.SlackChatBungee;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.Set;

/**
 * Created by david on 2/12.
 *
 * @author david
 */

class SpreadsheetsAPI {
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final Set<String> SCOPES = SheetsScopes.all();

    private FileDataStoreFactory dataStoreFactory;
    private HttpTransport httpTransport;
    private File clientSecret;
    private String name;

    SpreadsheetsAPI(String name, File dataStoreDir, File clientSecret) throws IOException, GeneralSecurityException {
        this.name = name;
        this.clientSecret = clientSecret;
        dataStoreFactory = new FileDataStoreFactory(dataStoreDir);
        httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }

    private Credential authorize() throws IOException {
        InputStream in = new FileInputStream(clientSecret);
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build();
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    Sheets getSheetsService() throws IOException {
        Credential credential = authorize();
        return new Sheets.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName(name).build();
    }
}
