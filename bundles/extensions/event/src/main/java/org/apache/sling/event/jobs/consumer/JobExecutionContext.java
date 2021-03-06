/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.jobs.consumer;

import aQute.bnd.annotation.ProviderType;

/**
 *
 * @since 1.1
 */
@ProviderType
public interface JobExecutionContext {

    /**
     * Report an async result.
     * @throws IllegalStateException If the job is not processed asynchronously
     *                               or if this method has already been called.
     */
    void asyncProcessingFinished(final JobExecutionResult result);

    /**
     * If a job is stoppable, it should periodically check this method
     * and stop processing if the method return <code>true</code>.
     * If a job is stopped and the job executor detects this, its up
     * to the implementation to decide the result of such a state.
     * There might be use cases where the job returns {@link JobStatus#SUCCEEDED}
     * although it didn't process everything, or {@link JobStatus#FAILED}
     * to retry later on or {@link JobStatus#CANCELLED}.
     * @return Whether this job has been stopped from the outside.
     */
    boolean isStopped();

    /**
     * Indicate that the job executor is able to report the progress.
     * The progress can either be reported by a step count,
     * assuming that all steps take roughly the same amount of time.
     * Or the progress can be reported by an ETA containing the number
     * of seconds the job needs to finish.
     * This method should only be called once, consecutive calls
     * have no effect.
     * By using a step count of 100, the progress can be displayed
     * in percentage.
     * @param steps Number of total steps or -1 if the number of
     *              steps is unknown.
     * @param eta Number of seconds the process should take or
     *        -1 of it's not known now.
     */
    void initProgress(final int steps, final long eta);

    /**
     * Update the progress by additionally marking the provided
     * number of steps as finished. If the total number of finished
     * steps is equal or higher to the initial number of steps
     * reported in {@link #initProgress(int, long)}, then the
     * job progress is assumed to be 100%.
     * This method has only effect if {@link #initProgress(int, long)}
     * has been called first with a positive number for steps
     * @param step The number of finished steps since the last call.
     */
    void incrementProgressCount(final int steps);

    /**
     * Update the progress to the new ETA.
     * This method has only effect if {@link #initProgress(int, long)}
     * has been called first.
     * @param eta The new ETA
     */
    void updateProgress(final long eta);

    /**
     * Log a message.
     * The message and the arguments are passed to the {@link java.text.MessageFormat}
     * class.
     * @param message A message
     * @param args Additional arguments
     */
    void log(final String message, final Object...args);

    ResultBuilder result(final String message);

    JobExecutionResult SUCCEEDED();

    JobExecutionResult FAILED();

    JobExecutionResult CANCELLED();

    public interface ResultBuilder {

        /**
         * @param retryDelayInMs The new retry delay in ms.
         */
        ResultBuilder retryDelay(final long retryDelayInMs);

        JobExecutionResult SUCCEEDED();

        JobExecutionResult FAILED();

        JobExecutionResult CANCELLED();
    }
}
