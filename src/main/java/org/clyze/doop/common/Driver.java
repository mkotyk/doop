package org.clyze.doop.common;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A driver for parallel fact generation.
 * @param <C>    class type
 * @param <F>    thread factory type that generates Runnable objects
 */
public abstract class Driver<C, F> {
    protected ExecutorService _executor;
    protected final F _factory;
    protected final int _cores;
    protected Set<C> _tmpClassGroup;
    protected int _classCounter;
    private final int _totalClasses;
    private final int _classSplit = 80;

    public Driver(F factory, int totalClasses, Integer cores) {
        this._factory = factory;
        this._totalClasses = totalClasses;
        this._cores = cores == null? Runtime.getRuntime().availableProcessors() : cores;
        this._classCounter = 0;
        this._tmpClassGroup = new HashSet<>();

        System.out.println("Fact generation cores: " + _cores);
    }

    private void initExecutor() {
        _classCounter = 0;
        _tmpClassGroup = new HashSet<>();

        if (_cores > 2) {
            _executor = new ThreadPoolExecutor(_cores /2, _cores, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        } else {
            // No scheduling happens in the case of one core/thread. ("Tasks are
            // guaranteed to execute sequentially, and no more than one task will
            // be active at any given time.")
            _executor = Executors.newSingleThreadExecutor();
        }
    }

    protected void doInParallel(Set<C> classesToProcess, Consumer<? super C> action) throws DoopErrorCodeException {
        initExecutor();
        classesToProcess.forEach(action);
        shutdownExecutor();
    }

    private void shutdownExecutor() throws DoopErrorCodeException {
        _executor.shutdown();
        try {
            _executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
            throw new DoopErrorCodeException(10);
        }
    }

    public void generateInParallel(Set<C> classesToProcess) throws DoopErrorCodeException {
        doInParallel(classesToProcess, this::generate);
    }


    public void writeInParallel(Set<C> classesToProcess) throws DoopErrorCodeException {
        doInParallel(classesToProcess, this::write);
    }

    protected void generate(C curClass) {
        _classCounter++;
        _tmpClassGroup.add(curClass);

        if ((_classCounter % _classSplit == 0) || (_classCounter == _totalClasses)) {
            _executor.execute(getFactGenRunnable());
            _tmpClassGroup = new HashSet<>();
        }
    }

    private void write(C curClass) {
        _classCounter++;
        _tmpClassGroup.add(curClass);

        if ((_classCounter % _classSplit == 0) || (_classCounter == _totalClasses)) {
            _executor.execute(getIRGenRunnable());
            _tmpClassGroup = new HashSet<>();
        }
    }

    protected abstract Runnable getFactGenRunnable();
    protected abstract Runnable getIRGenRunnable();
}