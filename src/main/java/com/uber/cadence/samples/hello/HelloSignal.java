/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.samples.hello;

import static com.uber.cadence.samples.common.SampleConstants.DOMAIN;

import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates asynchronous signalling of a workflow. Requires a local instance of Cadence server
 * to be running.
 */
@SuppressWarnings("ALL")
public class HelloSignal {

  static final String TASK_LIST = "HelloSignal";

  /** Activity interface is just a POJI. */
  public interface GreetingActivities {
    @ActivityMethod(scheduleToCloseTimeoutSeconds = 5)
    String composeGreeting(String greeting, String name);
  }

  static class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String greeting, String name) {
      return greeting + " " + name + "!";
    }
  }

  /** Workflow interface must have a method annotated with @WorkflowMethod. */
  public interface GreetingWorkflow {
    /**
     * @return list of greeting strings that were received through the waitForNameMethod. This
     *     method will block until the number of greetings specified are received.
     */
    @WorkflowMethod
    List<String> getGreetings();

    /** Receives name through an external signal. */
    @SignalMethod
    void waitForName(String name);

    /** Receives name through an external signal. */
    @SignalMethod
    void exit();
  }

  /** GreetingWorkflow implementation that returns a greeting. */
  public static class GreetingWorkflowImpl implements GreetingWorkflow {

    private final GreetingActivities activities =
        Workflow.newActivityStub(GreetingActivities.class);

    List<String> messageQueue = new ArrayList<>(10);
    boolean exit = false;

    @Override
    public List<String> getGreetings() {
      List<String> receivedMessages = new ArrayList<>(10);

      while (true) {
        Workflow.await(() -> !messageQueue.isEmpty() || exit);
        if (messageQueue.isEmpty() && exit) {
          return receivedMessages;
        }
        String message = messageQueue.remove(0);
        receivedMessages.add(message);
      }
    }

    @Override
    public void waitForName(String name) {
      messageQueue.add(activities.composeGreeting("Hello", name));
    }

    @Override
    public void exit() {
      exit = true;
    }
  }

  public static void main(String[] args) throws Exception {
    // Start a worker that hosts the workflow implementation.
    Worker.Factory factory = new Worker.Factory(DOMAIN);
    Worker worker = factory.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);
    factory.start();

    // Start a workflow execution. Usually this is done from another program.
    WorkflowClient workflowClient = WorkflowClient.newInstance(DOMAIN);
    // Get a workflow stub using the same task list the worker uses.
    WorkflowOptions workflowOptions =
        new WorkflowOptions.Builder()
            .setTaskList(TASK_LIST)
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(30))
            .build();
    GreetingWorkflow workflow =
        workflowClient.newWorkflowStub(GreetingWorkflow.class, workflowOptions);
    workflow.wait();
    // Start workflow asynchronously to not use another thread to signal.
    WorkflowClient.start(workflow::getGreetings);
    // After start for getGreeting returns, the workflow is guaranteed to be started.
    // So we can send a signal to it using workflow stub.
    // This workflow keeps receiving signals until exit is called
    workflow.waitForName("World");
    workflow.waitForName("Universe");
    workflow.exit();
    // Calling synchronous getGreeting after workflow has started reconnects to the existing
    // workflow and blocks until a result is available. Note that this behavior assumes that
    // WorkflowOptions are not configured with WorkflowIdReusePolicy.AllowDuplicate. In that case
    // the call would fail with WorkflowExecutionAlreadyStartedException.
    List<String> greetings = workflow.getGreetings();
    System.out.println(greetings);
    System.exit(0);
  }
}
