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

package org.apache.druid.java.util.common.lifecycle;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.logger.Logger;

import java.io.Closeable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A manager of object Lifecycles.
 *
 * This object has methods for registering objects that should be started and stopped.  The Lifecycle allows for
 * four stages: {@link Stage#INIT}, {@link Stage#NORMAL}, {@link Stage#SERVER}, and {@link Stage#ANNOUNCEMENTS}.
 *
 * Things added at {@link Stage#INIT} will be started first (in the order that they are added to the Lifecycle instance)
 * and then things added at {@link Stage#NORMAL}, then {@link Stage#SERVER}, and finally, {@link Stage#ANNOUNCEMENTS}
 * will be started.
 *
 * The close operation goes in reverse order, starting with the last thing added at {@link Stage#ANNOUNCEMENTS} and
 * working backwards.
 *
 * Conceptually, the stages have the following purposes:
 *  - {@link Stage#INIT}: Currently, this stage is used exclusively for log4j initialization, since almost everything
 *    needs logging and it should be the last thing to shutdown. Any sort of bootstrapping object that provides
 *    something that should be initialized before nearly all other Lifecycle objects could also belong here (if it
 *    doesn't need logging during start or stop).
 *  - {@link Stage#NORMAL}: This is the default stage. Most objects will probably make the most sense to be registered
 *    at this level, with the exception of any form of server or service announcements
 *  - {@link Stage#SERVER}: This lifecycle stage is intended for all 'server' objects, for example,
 *    org.apache.druid.server.initialization.jetty.JettyServerModule, but any sort of 'server' that expects most (or
 *    some specific) Lifecycle objects to be initialized by the time it starts, and still available at the time it stops
 *    can logically live in this stage.
 *  - {@link Stage#ANNOUNCEMENTS}: Any object which announces to a cluster this servers location belongs in this stage.
 *    By being last, we can be sure that all servers are initialized before we advertise the endpoint locations, and
 *    also can be sure that we un-announce these advertisements prior to the Stage.SERVER objects stop.
 *
 * There are two sets of methods to add things to the Lifecycle.  One set that will just add instances and enforce that
 * start() has not been called yet.  The other set will add instances and, if the lifecycle is already started, start
 * them.
 */
public class Lifecycle
{
  private static final Logger log = new Logger(Lifecycle.class);

  public enum Stage
  {
    INIT,
    NORMAL,
    SERVER,
    ANNOUNCEMENTS
  }

  private enum State
  {
    /** Lifecycle's state before {@link #start()} is called. */
    NOT_STARTED,
    /** Lifecycle's state since {@link #start()} and before {@link #stop()} is called. */
    RUNNING,
    /** Lifecycle's state since {@link #stop()} is called. */
    STOP
  }

  private final NavigableMap<Stage, CopyOnWriteArrayList<Handler>> handlers;
  /** This lock is used to linearize all calls to Handler.start() and Handler.stop() on the managed handlers. */
  private final Lock startStopLock = new ReentrantLock();
  private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
  private Stage currStage = null;
  private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
  private final String name;

  public Lifecycle()
  {
    this("anonymous");
  }

  public Lifecycle(String name)
  {
    Preconditions.checkArgument(StringUtils.isNotEmpty(name), "Lifecycle name must not be null or empty");
    this.name = name;
    handlers = new TreeMap<>();
    for (Stage stage : Stage.values()) {
      handlers.put(stage, new CopyOnWriteArrayList<>());
    }
  }

  /**
   * Adds a "managed" instance (annotated with {@link LifecycleStart} and {@link LifecycleStop}) to the Lifecycle at
   * Stage.NORMAL. If the lifecycle has already been started, it throws an {@link ISE}
   *
   * @param o The object to add to the lifecycle
   *
   * @throws ISE {@link Lifecycle#addHandler(Handler, Stage)}
   */
  public <T> T addManagedInstance(T o)
  {
    addHandler(new AnnotationBasedHandler(o));
    return o;
  }

  /**
   * Adds a "managed" instance (annotated with {@link LifecycleStart} and {@link LifecycleStop}) to the Lifecycle.
   * If the lifecycle has already been started, it throws an {@link ISE}
   *
   * @param o     The object to add to the lifecycle
   * @param stage The stage to add the lifecycle at
   *
   * @throws ISE {@link Lifecycle#addHandler(Handler, Stage)}
   */
  public <T> T addManagedInstance(T o, Stage stage)
  {
    addHandler(new AnnotationBasedHandler(o), stage);
    return o;
  }

  /**
   * Adds an instance with a start() and/or close() method to the Lifecycle at Stage.NORMAL.  If the lifecycle has
   * already been started, it throws an {@link ISE}
   *
   * @param o The object to add to the lifecycle
   *
   * @throws ISE {@link Lifecycle#addHandler(Handler, Stage)}
   */
  public <T> T addStartCloseInstance(T o)
  {
    addHandler(new StartCloseHandler(o));
    return o;
  }

  /**
   * Adds an instance with a start() and/or close() method to the Lifecycle.  If the lifecycle has already been started,
   * it throws an {@link ISE}
   *
   * @param o     The object to add to the lifecycle
   * @param stage The stage to add the lifecycle at
   *
   * @throws ISE {@link Lifecycle#addHandler(Handler, Stage)}
   */
  public <T> T addStartCloseInstance(T o, Stage stage)
  {
    addHandler(new StartCloseHandler(o), stage);
    return o;
  }

  /**
   * Adds a handler to the Lifecycle at the Stage.NORMAL stage. If the lifecycle has already been started, it throws
   * an {@link ISE}
   *
   * @param handler The hander to add to the lifecycle
   *
   * @throws ISE {@link Lifecycle#addHandler(Handler, Stage)}
   */
  public void addHandler(Handler handler)
  {
    addHandler(handler, Stage.NORMAL);
  }

  /**
   * Adds a handler to the Lifecycle. If the lifecycle has already been started, it throws an {@link ISE}
   *
   * @param handler The hander to add to the lifecycle
   * @param stage   The stage to add the lifecycle at
   *
   * @throws ISE indicates that the lifecycle has already been started and thus cannot be added to
   */
  public void addHandler(Handler handler, Stage stage)
  {
    if (!startStopLock.tryLock()) {
      throw new ISE("Cannot add a handler in the process of Lifecycle starting or stopping");
    }
    try {
      if (!state.get().equals(State.NOT_STARTED)) {
        throw new ISE("Cannot add a handler after the Lifecycle has started, it doesn't work that way.");
      }
      handlers.get(stage).add(handler);
    }
    finally {
      startStopLock.unlock();
    }
  }

  /**
   * Adds a "managed" instance (annotated with {@link LifecycleStart} and {@link LifecycleStop}) to the Lifecycle at
   * Stage.NORMAL and starts it if the lifecycle has already been started.
   *
   * @param o The object to add to the lifecycle
   *
   * @throws Exception {@link Lifecycle#addMaybeStartHandler(Handler, Stage)}
   */
  public <T> T addMaybeStartManagedInstance(T o) throws Exception
  {
    addMaybeStartHandler(new AnnotationBasedHandler(o));
    return o;
  }

  /**
   * Adds a "managed" instance (annotated with {@link LifecycleStart} and {@link LifecycleStop}) to the Lifecycle
   * and starts it if the lifecycle has already been started.
   *
   * @param o     The object to add to the lifecycle
   * @param stage The stage to add the lifecycle at
   *
   * @throws Exception {@link Lifecycle#addMaybeStartHandler(Handler, Stage)}
   */
  public <T> T addMaybeStartManagedInstance(T o, Stage stage) throws Exception
  {
    addMaybeStartHandler(new AnnotationBasedHandler(o), stage);
    return o;
  }

  /**
   * Adds an instance with a start() and/or close() method to the Lifecycle at Stage.NORMAL and starts it if the
   * lifecycle has already been started.
   *
   * @param o The object to add to the lifecycle
   *
   * @throws Exception {@link Lifecycle#addMaybeStartHandler(Handler, Stage)}
   */
  public <T> T addMaybeStartStartCloseInstance(T o) throws Exception
  {
    addMaybeStartHandler(new StartCloseHandler(o));
    return o;
  }

  /**
   * Adds an instance with a start() and/or close() method to the Lifecycle and starts it if the lifecycle has
   * already been started.
   *
   * @param o     The object to add to the lifecycle
   * @param stage The stage to add the lifecycle at
   *
   * @throws Exception {@link Lifecycle#addMaybeStartHandler(Handler, Stage)}
   */
  public <T> T addMaybeStartStartCloseInstance(T o, Stage stage) throws Exception
  {
    addMaybeStartHandler(new StartCloseHandler(o), stage);
    return o;
  }

  /**
   * Adds a Closeable instance to the lifecycle at {@link Stage#NORMAL} stage, doesn't try to call any "start" method on
   * it, use {@link #addStartCloseInstance(Object)} instead if you need the latter behaviour.
   */
  public <T extends Closeable> T addCloseableInstance(T o)
  {
    addHandler(new CloseableHandler(o));
    return o;
  }

  /**
   * Adds a handler to the Lifecycle at the Stage.NORMAL stage and starts it if the lifecycle has already been started.
   *
   * @param handler The hander to add to the lifecycle
   *
   * @throws Exception {@link Lifecycle#addMaybeStartHandler(Handler, Stage)}
   */
  public void addMaybeStartHandler(Handler handler) throws Exception
  {
    addMaybeStartHandler(handler, Stage.NORMAL);
  }

  /**
   * Adds a handler to the Lifecycle and starts it if the lifecycle has already been started.
   *
   * @param handler The hander to add to the lifecycle
   * @param stage   The stage to add the lifecycle at
   *
   * @throws Exception an exception thrown from handler.start().  If an exception is thrown, the handler is *not* added
   */
  public void addMaybeStartHandler(Handler handler, Stage stage) throws Exception
  {
    if (!startStopLock.tryLock()) {
      // (*) This check is why the state should be changed before startStopLock.lock() in stop(). This check allows to
      // spot wrong use of Lifecycle instead of entering deadlock, like https://github.com/apache/druid/issues/3579.
      if (state.get().equals(State.STOP)) {
        throw new ISE("Cannot add a handler in the process of Lifecycle stopping");
      }
      startStopLock.lock();
    }
    try {
      if (state.get().equals(State.STOP)) {
        throw new ISE("Cannot add a handler after the Lifecycle has stopped");
      }
      if (state.get().equals(State.RUNNING)) {
        if (stage.compareTo(currStage) <= 0) {
          handler.start();
        }
      }
      handlers.get(stage).add(handler);
    }
    finally {
      startStopLock.unlock();
    }
  }

  public void start() throws Exception
  {
    startStopLock.lock();
    try {
      if (!state.get().equals(State.NOT_STARTED)) {
        throw new ISE("Already started");
      }
      if (!state.compareAndSet(State.NOT_STARTED, State.RUNNING)) {
        throw new ISE("stop() is called concurrently with start()");
      }
      for (Map.Entry<Stage, ? extends List<Handler>> e : handlers.entrySet()) {
        currStage = e.getKey();
        log.info("Starting lifecycle [%s] stage [%s]", name, currStage.name());
        for (Handler handler : e.getValue()) {
          handler.start();
        }
      }
      log.info("Successfully started lifecycle [%s]", name);
    }
    finally {
      startStopLock.unlock();
    }
  }

  public void stop()
  {
    // This CAS outside of a block guarded by startStopLock is the only reason why state is AtomicReference rather than
    // a simple variable. State change before startStopLock.lock() is needed for the new state visibility during the
    // check in addMaybeStartHandler() marked by (*).
    if (!state.compareAndSet(State.RUNNING, State.STOP)) {
      log.info("Lifecycle [%s] already stopped and stop was called. Silently skipping", name);
      return;
    }
    startStopLock.lock();
    try {
      Exception thrown = null;

      for (Stage s : handlers.navigableKeySet().descendingSet()) {
        log.info("Stopping lifecycle [%s] stage [%s]", name, s.name());
        for (Handler handler : Lists.reverse(handlers.get(s))) {
          try {
            handler.stop();
          }
          catch (Exception e) {
            log.warn(e, "Lifecycle [%s] encountered exception while stopping %s", name, handler);
            if (thrown == null) {
              thrown = e;
            } else {
              thrown.addSuppressed(e);
            }
          }
        }
      }

      if (thrown != null) {
        throw new RuntimeException(thrown);
      }
    }
    finally {
      startStopLock.unlock();
    }
  }

  public void ensureShutdownHook()
  {
    if (shutdownHookRegistered.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(
          new Thread(
              new Runnable()
              {
                @Override
                public void run()
                {
                  log.info("Lifecycle [%s] running shutdown hook", name);
                  stop();
                }
              }
          )
      );
    }
  }

  public void join() throws InterruptedException
  {
    ensureShutdownHook();
    Thread.currentThread().join();
  }

  public interface Handler
  {
    void start() throws Exception;

    void stop();
  }

  private static class AnnotationBasedHandler implements Handler
  {
    private static final Logger log = new Logger(AnnotationBasedHandler.class);

    private final Object o;

    public AnnotationBasedHandler(Object o)
    {
      this.o = o;
    }

    @Override
    public void start() throws Exception
    {
      for (Method method : o.getClass().getMethods()) {
        boolean doStart = false;
        for (Annotation annotation : method.getAnnotations()) {
          if (LifecycleStart.class.getName().equals(annotation.annotationType().getName())) {
            doStart = true;
            break;
          }
        }
        if (doStart) {
          log.info("Starting lifecycle [%s#%s]", o.getClass().getSimpleName(), method.getName());
          method.invoke(o);
        }
      }
    }

    @Override
    public void stop()
    {
      for (Method method : o.getClass().getMethods()) {
        boolean doStop = false;
        for (Annotation annotation : method.getAnnotations()) {
          if (LifecycleStop.class.getName().equals(annotation.annotationType().getName())) {
            doStop = true;
            break;
          }
        }
        if (doStop) {
          log.debug("Stopping lifecyle [%s#%s].", o.getClass().getSimpleName(), method.getName());
          try {
            method.invoke(o);
          }
          catch (Exception e) {
            log.error(e, "Exception when stopping method[%s] on object[%s]", method, o);
          }
        }
      }
    }
  }

  private static class StartCloseHandler implements Handler
  {
    private static final Logger log = new Logger(StartCloseHandler.class);

    private final Object o;
    private final Method startMethod;
    private final Method stopMethod;

    public StartCloseHandler(Object o)
    {
      this.o = o;
      try {
        startMethod = o.getClass().getMethod("start");
        stopMethod = o.getClass().getMethod("close");
      }
      catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }


    @Override
    public void start() throws Exception
    {
      log.info("Starting object[%s]", o);
      startMethod.invoke(o);
    }

    @Override
    public void stop()
    {
      log.info("Stopping object[%s]", o);
      try {
        stopMethod.invoke(o);
      }
      catch (Exception e) {
        log.error(e, "Unable to invoke stopMethod() on %s", o.getClass());
      }
    }
  }

  private static class CloseableHandler implements Handler
  {
    private static final Logger log = new Logger(CloseableHandler.class);
    private final Closeable o;

    private CloseableHandler(Closeable o)
    {
      this.o = o;
    }

    @Override
    public void start()
    {
      // do nothing
    }

    @Override
    public void stop()
    {
      log.info("Closing object[%s]", o);
      try {
        o.close();
      }
      catch (Exception e) {
        log.error(e, "Exception when closing object [%s]", o);
      }
    }
  }
}
