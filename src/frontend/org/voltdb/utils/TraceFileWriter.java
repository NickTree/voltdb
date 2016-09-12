/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

import org.codehaus.jackson.map.ObjectMapper;
import org.voltcore.logging.Level;
import org.voltcore.logging.VoltLogger;
import org.voltdb.VoltDB;


/**
 * Reads trace events from VoltTrace queue and writes them to files.
 */
public class TraceFileWriter implements Runnable {
    // If we reach MAX_OPEN_TRACES, old entries with last access time earlier than this delay value will be removed.
    public static final String PURGE_SECONDS_DELAY_PROP = "TRACE_PURGE_DELAY_SECONDS";
    public static final int PURGE_SECONDS_DELAY = Integer.getInteger(PURGE_SECONDS_DELAY_PROP, 30);
    static long PURGE_MILLIS_DELAY = PURGE_SECONDS_DELAY*1000;
    // Number of open files allowed.
    public static final int MAX_OPEN_TRACES = 16;

    private static final VoltLogger s_logger = new VoltLogger("TRACER");

    private final ObjectMapper m_jsonMapper = new ObjectMapper();
    private final String m_traceFilesDir;
    private final VoltTrace m_voltTrace;
    private boolean m_shutdown;
    private BufferedWriter m_fileWriter = null;
    private FileTimeInfo m_fileTimeInfo = null;

    public TraceFileWriter(VoltTrace voltTrace) {
        m_voltTrace = voltTrace;
        if (VoltDB.isThisATest()) {
            m_traceFilesDir = ".";
        } else {
            m_traceFilesDir = VoltDB.instance().getCatalogContext().getDeployment()
                .getPaths().getVoltdbroot().getPath();
        }
    }

    public void run() {
        while (!m_shutdown) {
            try {
                VoltTrace.TraceEvent event = m_voltTrace.takeEvent();
                if (event == null) {
                    LockSupport.parkNanos(1L);
                    continue;
                }

                if (event.getType()==VoltTrace.TraceEventType.VOLT_INTERNAL_CLOSE) {
                    handleCloseEvent();
                } else if (event.getType()== VoltTrace.TraceEventType.VOLT_INTERNAL_CLOSE_ALL) {
                    handleCloseEvent();
                    m_shutdown = true;
                } else {
                    startTraceFile(event);
                }
                if (m_fileWriter != null) {
                    m_fileTimeInfo.updateAccessMillis();
                    event.setSyncNanos(m_fileTimeInfo.m_syncNanos);
                    String json = m_jsonMapper.writeValueAsString(event);
                    m_fileWriter.write(",");
                    m_fileWriter.newLine();
                    m_fileWriter.write(json);
                    m_fileWriter.flush();
                }
            } catch(InterruptedException e) {
                s_logger.info("Volt trace file writer thread interrupted. Stopping trace file writer.");
                m_shutdown = true;
            } catch(IOException e) { // also catches JSON exceptions
                s_logger.warn("Unexpected IO exception in trace file writer. Stopping trace file writer.", e);
                m_shutdown = true;
            }
        }
    }

    private void handleCloseEvent() {
        BufferedWriter bw = m_fileWriter;
        if (bw==null) return;

        try {
            bw.newLine();
            bw.write("]");
            bw.newLine();
            bw.flush();
            bw.close();
        } catch(IOException e) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Exception closing trace file buffered writer", e);
            }
        }
        m_fileWriter = null;
        m_fileTimeInfo = null;
    }

    private void startTraceFile(VoltTrace.TraceEvent event) throws IOException {
        BufferedWriter bw = m_fileWriter;
        if (bw != null) return;

        File file = new File("volt.trace");
        // if the file exists already, we don't want to overwrite
        if (file.exists()) {
            s_logger.rateLimitedLog(60, Level.WARN, null,
                "Trace file already exists. Dropping trace events to avoid overwriting the file");
            return;
        }

        // Uses the default platform encoding for now.
        bw = new BufferedWriter(new FileWriter(new File(m_traceFilesDir, "volt.trace")));
        m_fileWriter = bw;
        m_fileTimeInfo = new FileTimeInfo(event.getNanos());
        bw.write("[");
        bw.flush();
    }

    private static class FileTimeInfo {
        final long m_syncNanos;
        long m_accessMillis;

        public FileTimeInfo(long syncNanos) {
            m_syncNanos = syncNanos;
            m_accessMillis = System.currentTimeMillis();
        }

        public void updateAccessMillis() {
            m_accessMillis = System.currentTimeMillis();
        }
    }
}
