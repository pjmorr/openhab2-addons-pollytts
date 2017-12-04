/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.voice.pollytts.internal.cloudapi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a cache for the retrieved audio data. It will preserve
 * them in file system, as audio files with an additional .txt file to indicate
 * what content is in the audio file.
 *
 * @author Jochen Hiller - Initial contribution
 * @author Robert Hillman - converted to Polly API to utilize unique voice names
 * @author Robert Hillman - added cache management to delete aged entries
 */
public class CachedPollyTTSCloudImplementation extends PollyTTSCloudImplementation {

    private final Logger logger = LoggerFactory.getLogger(CachedPollyTTSCloudImplementation.class);

    private final File cacheFolder;

    public CachedPollyTTSCloudImplementation(String cacheFolderName) {
        if (cacheFolderName == null) {
            throw new RuntimeException("Folder for cache must be defined");
        }
        // Lazy create the cache folder
        cacheFolder = new File(cacheFolderName);
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs();
        }
    }

    public File getTextToSpeechAsFile(String text, String label, String audioFormat) throws IOException {
        String fileNameInCache = getUniqeFilenameForText(text, label);
        // check if in cache
        File audioFileInCache = new File(cacheFolder, fileNameInCache + "." + audioFormat.toLowerCase());
        if (audioFileInCache.exists()) {
            // update use date
            updateTimeStamp(audioFileInCache);
            updateTimeStamp(new File(cacheFolder, fileNameInCache + ".txt"));
            purgeAgedFiles();
            return audioFileInCache;
        }

        // if not in cache, get audio data and put to cache
        try (InputStream is = super.getTextToSpeech(text, label, audioFormat);
                FileOutputStream fos = new FileOutputStream(audioFileInCache);) {
            copyStream(is, fos);
            // write text to file for transparency too
            // this allows to know which contents is in which audio file
            File txtFileInCache = new File(cacheFolder, fileNameInCache + ".txt");
            writeText(txtFileInCache, text);
            // return from cache
            return audioFileInCache;
        } catch (IOException ex) {
            logger.warn("Could not write {} to cache, return null", audioFileInCache, ex);
            return null;
        }
    }

    /**
     * Gets a unique filename for a give text, by creating a MD5 hash of it. It
     * will be preceded by the voice label.
     *
     * Sample: "Robert_00a2653ac5f77063bc4ea2fee87318d3"
     */
    String getUniqeFilenameForText(String text, String label) {
        try {
            byte[] bytesOfMessage = text.getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Hash = md.digest(bytesOfMessage);
            BigInteger bigInt = new BigInteger(1, md5Hash);
            String hashtext = bigInt.toString(16);
            // Now we need to zero pad it if you actually want the full 32
            // chars.
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            String fileName = label + "_" + hashtext;
            return fileName;
        } catch (UnsupportedEncodingException ex) {
            // should not happen
            logger.error("Could not create MD5 hash for '{}'", text, ex);
            return null;
        } catch (NoSuchAlgorithmException ex) {
            // should not happen
            logger.error("Could not create MD5 hash for '{}'", text, ex);
            return null;
        }
    }

    // helper methods

    private void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] bytes = new byte[4096];
        int read = inputStream.read(bytes, 0, 4096);
        while (read > 0) {
            outputStream.write(bytes, 0, read);
            read = inputStream.read(bytes, 0, 4096);
        }
    }

    private void writeText(File file, String text) throws IOException {
        OutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(file);
            outputStream.write(text.getBytes("UTF-8"));
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private void updateTimeStamp(File file) throws IOException {
        long timestamp = System.currentTimeMillis();
        // update use date for cache management
        file.setLastModified(timestamp);
    }

    public void purgeAgedFiles() throws IOException {
        // just exit if expiration set to 0/disabled
        if (PollyClientConfig.getExpireDate() == 0) {
            return;
        }
        long now = new Date().getTime();
        long diff = now - PollyClientConfig.getlastDelete();
        // only execute ~ once every 2 days if cache called
        long oneDay = 24 * 60 * 60 * 1000;
        logger.debug("PollyTTS cache cleaner lastdelete {}", diff);
        if (diff > (2 * oneDay)) {
            PollyClientConfig.setLastDelete(now);
            long xDaysAgo = PollyClientConfig.getExpireDate() * oneDay;
            // Now search folders and delete old files
            int filesDeleted = 0;
            for (File f : cacheFolder.listFiles()) {
                diff = now - f.lastModified();
                if (diff > xDaysAgo) {
                    filesDeleted++;
                    f.delete();
                }
            }
            logger.info("PollyTTS cache cleaner deleted '{}' aged files", filesDeleted);
        }
    }
}
