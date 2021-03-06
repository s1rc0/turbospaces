/**
 * Copyright (C) 2011-2012 Andrey Borisov <aandrey.borisov@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turbospaces.core;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import sun.misc.Unsafe;

import com.esotericsoftware.minlog.Log;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Uninterruptibles;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.util.Util;
import com.turbospaces.api.CapacityRestriction;
import com.turbospaces.api.SpaceCapacityOverflowException;
import com.turbospaces.api.SpaceMemoryOverflowException;
import com.turbospaces.offmemory.ByteArrayPointer;

/**
 * This is placeholder for all utility method of turbospaces-collections maven project. We prefer creation of one such
 * global utility class (something to jgroup's Util) rather than multiple small classes in-place. You can still
 * argue...
 * 
 * @since 0.1
 */
public class JVMUtil {
    /**
     * This method guarantees that garbage collection is done unlike <code>{@link System#gc()}</code>. Please be careful
     * with extra usage of this method, we created it for testing purposes mostly.
     */
    public static void gc() {
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<Object>( obj );
        obj = null;
        while ( ref.get() != null )
            System.gc();
    }

    /**
     * This method guarantees that garbage collection is done after JVM shutdown triggering but before JVM
     * shutdown.Please be careful
     * with extra usage of this method, we created it for testing purposes mostly.
     */
    public static void gcOnExit() {
        Runtime.getRuntime().addShutdownHook( new Thread() {
            @Override
            public void run() {
                gc();
            }
        } );
    }

    /**
     * get the declared field by name.
     * 
     * @param clazz
     *            target class
     * @param name
     *            field name
     * @return reflection field
     */
    public static Field fieldFor(final Class<?> clazz,
                                 final String name) {
        for ( ;; )
            try {
                return clazz.getDeclaredField( name );
            }
            catch ( Exception e ) {
                Log.warn( String.format(
                        "unable to lookup %s field of class %s, available fields = %s",
                        name,
                        clazz.getName(),
                        Arrays.toString( clazz.getFields() ) ) );
                Throwables.propagate( e );
            }
    }

    /**
     * extract property value using Unsafe tricks.
     * 
     * @param source
     *            properties holder
     * @param valueType
     *            field type
     * @param fieldOffset
     *            field offset
     * @return extracted value
     */
    @SuppressWarnings("unchecked")
    public static <T> T getPropertyValueUnsafe(final Object source,
                                               final Class<T> valueType,
                                               final long fieldOffset) {
        Unsafe unsafe = Util.getUnsafe();
        Object value = null;

        if ( valueType.isPrimitive() ) {
            if ( valueType == int.class )
                value = unsafe.getInt( source, fieldOffset );
            if ( valueType == short.class )
                value = unsafe.getShort( source, fieldOffset );
            else if ( valueType == long.class )
                value = unsafe.getLong( source, fieldOffset );
            else if ( valueType == float.class )
                value = unsafe.getFloat( source, fieldOffset );
            else if ( valueType == double.class )
                value = unsafe.getDouble( source, fieldOffset );
            else if ( valueType == char.class )
                value = unsafe.getChar( source, fieldOffset );
            else if ( valueType == byte.class )
                value = unsafe.getByte( source, fieldOffset );
            else if ( valueType == boolean.class )
                value = unsafe.getBoolean( source, fieldOffset );
        }
        else
            value = unsafe.getObject( source, fieldOffset );
        return (T) value;
    }

    /**
     * set property value using Unsafe tricks.
     * 
     * @param source
     *            properties holder
     * @param value
     *            field value to set
     * @param valueType
     *            field type
     * @param fieldOffset
     *            field offset
     */
    public static <T> void setPropertyValueUnsafe(final Object source,
                                                  final T value,
                                                  final Class<T> valueType,
                                                  final long fieldOffset) {
        Unsafe unsafe = Util.getUnsafe();

        if ( valueType.isPrimitive() ) {
            if ( valueType == int.class )
                unsafe.putInt( source, fieldOffset, ( (Integer) value ).intValue() );
            if ( valueType == short.class )
                unsafe.putShort( source, fieldOffset, ( (Short) value ).shortValue() );
            else if ( valueType == long.class )
                unsafe.putLong( source, fieldOffset, ( (Long) value ).longValue() );
            else if ( valueType == float.class )
                unsafe.putFloat( source, fieldOffset, ( (Float) value ).floatValue() );
            else if ( valueType == double.class )
                unsafe.putDouble( source, fieldOffset, ( (Double) value ).doubleValue() );
            else if ( valueType == char.class )
                unsafe.putChar( source, fieldOffset, ( (Character) value ).charValue() );
            else if ( valueType == byte.class )
                unsafe.putByte( source, fieldOffset, ( (Byte) value ).byteValue() );
            else if ( valueType == boolean.class )
                unsafe.putBoolean( source, fieldOffset, ( (Boolean) value ).booleanValue() );
        }
        else
            unsafe.putObject( source, fieldOffset, value );
    }

    /**
     * start new thread and execute client's runnable task. Wait for thread completition. Catch execution exception and
     * return it. If there is not such execution exception, raise {@link AssertionError}.</p>
     * 
     * @param runnable
     *            client's task
     * @return execution exception if any
     */
    public static Exception runAndGetExecutionException(final Runnable runnable) {
        final MutableObject<Exception> ex = new MutableObject<Exception>();
        Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    runnable.run();
                }
                catch ( Exception e ) {
                    Log.error( e.getMessage(), e );
                    ex.set( e );
                }
            }
        };
        thread.start();
        Uninterruptibles.joinUninterruptibly( thread );
        if ( ex.get() == null )
            throw new AssertionError( "there is no exception!" );
        return ex.get();
    }

    /**
     * repeats the task action totalIterationsCount times concurrently(you provide how many threads and callback
     * function) - this is general purpose utility.</p>
     * 
     * <strong>NOTE :</strong> this method returns all caught exceptions and you should at least use
     * <code>JUnit.Asser.assertTrue(repeateConcurrenlty.size(), 0)</code> or something similar to check that there are
     * no execution errors.
     * 
     * @param threads
     *            number of concurrent threads
     * @param totalIterationsCount
     *            how many times to repeat task execution concurrently
     * @param task
     *            the action which needs to be performed
     * @return all errors from thread's execution
     */
    public static <T> List<Throwable> repeatConcurrently(final int threads,
                                                         final int totalIterationsCount,
                                                         final Function<Integer, Object> task) {
        final AtomicInteger atomicLong = new AtomicInteger( totalIterationsCount );
        final CountDownLatch countDownLatch = new CountDownLatch( threads );
        final LinkedList<Throwable> errors = Lists.newLinkedList();
        for ( int j = 0; j < threads; j++ ) {
            Thread thread = new Thread( new Runnable() {

                @Override
                public void run() {
                    try {
                        int l;
                        while ( ( l = atomicLong.decrementAndGet() ) >= 0 )
                            try {
                                task.apply( l );
                            }
                            catch ( Throwable e ) {
                                Log.error( e.getMessage(), e );
                                errors.add( e );
                                Throwables.propagate( e );
                            }
                    }
                    finally {
                        countDownLatch.countDown();
                    }
                }
            } );
            thread.setName( String.format( "RepeateConcurrentlyThread-%s:{%s}", j, task.toString() ) );
            thread.start();
        }
        Uninterruptibles.awaitUninterruptibly( countDownLatch );
        return errors;
    }

    /**
     * repeats the task action totalIterationsCount times concurrently - similar to
     * {@link #repeatConcurrently(int, int, Function)} but you can pass {@link Runnable} instead of {@link Function}.
     * 
     * @param threads
     *            number of concurrent threads
     * @param totalIterationsCount
     *            how many times to repeat task execution concurrently
     * @param task
     *            the action which needs to be performed (runnable task)
     * @return all errors from thread's execution
     * @see #repeatConcurrently(int, int, Function)
     */
    public static <T> List<Throwable> repeatConcurrently(final int threads,
                                                         final int totalIterationsCount,
                                                         final Runnable task) {
        return repeatConcurrently( threads, totalIterationsCount, new Function<Integer, Object>() {

            @Override
            public Object apply(final Integer iteration) {
                task.run();
                return this;
            }
        } );
    }

    /**
     * re-hashes a 4-byte sequence (Java int) using murmur3 hash algorithm.</p>
     * 
     * @param hash
     *            bad quality hash
     * @return good general purpose hash
     */
    public static int murmurRehash(final int hash) {
        int k = hash;

        k ^= k >>> 16;
        k *= 0x85ebca6b;
        k ^= k >>> 13;
        k *= 0xc2b2ae35;
        k ^= k >>> 16;

        return k;
    }

    /**
     * re-hashes a 4-byte sequence (Java int) using standard JDK re-hash algorithm.</p>
     * 
     * @param hash
     *            bad quality hash
     * @return good hash
     */
    public static int jdkRehash(final int hash) {
        int h = hash;

        h += h << 15 ^ 0xffffcd7d;
        h ^= h >>> 10;
        h += h << 3;
        h ^= h >>> 6;
        h += ( h << 2 ) + ( h << 14 );

        return h ^ h >>> 16;
    }

    /**
     * Determine if the given objects are equal, returning <code>true</code> if both are <code>null</code> or
     * <code>false</code> if only one is <code>null</code>.
     * <p>
     * Compares arrays with <code>Arrays.equals</code>, performing an equality check based on the array elements rather
     * than the array reference.
     * 
     * @param o1
     *            first Object to compare
     * @param o2
     *            second Object to compare
     * @return whether the given objects are equal
     * @see java.util.Arrays#equals
     */
    public static boolean equals(final Object o1,
                                 final Object o2) {
        if ( o1 == o2 )
            return true;
        if ( o1 == null || o2 == null )
            return false;
        if ( o1.equals( o2 ) )
            return true;
        if ( o1.getClass().isArray() && o2.getClass().isArray() ) {
            if ( o1 instanceof Object[] && o2 instanceof Object[] )
                return Arrays.equals( (Object[]) o1, (Object[]) o2 );
            if ( o1 instanceof boolean[] && o2 instanceof boolean[] )
                return Arrays.equals( (boolean[]) o1, (boolean[]) o2 );
            if ( o1 instanceof byte[] && o2 instanceof byte[] )
                return Arrays.equals( (byte[]) o1, (byte[]) o2 );
            if ( o1 instanceof char[] && o2 instanceof char[] )
                return Arrays.equals( (char[]) o1, (char[]) o2 );
            if ( o1 instanceof double[] && o2 instanceof double[] )
                return Arrays.equals( (double[]) o1, (double[]) o2 );
            if ( o1 instanceof float[] && o2 instanceof float[] )
                return Arrays.equals( (float[]) o1, (float[]) o2 );
            if ( o1 instanceof int[] && o2 instanceof int[] )
                return Arrays.equals( (int[]) o1, (int[]) o2 );
            if ( o1 instanceof long[] && o2 instanceof long[] )
                return Arrays.equals( (long[]) o1, (long[]) o2 );
            if ( o1 instanceof short[] && o2 instanceof short[] )
                return Arrays.equals( (short[]) o1, (short[]) o2 );
        }
        return false;
    }

    /**
     * ensure that the space buffer does not violate space capacity and can add new byte array pointer.
     * 
     * @param pointer
     *            byte array pointer
     * @param capacityRestriction
     *            capacity restriction configuration
     * @param memoryUsed
     *            how many bytes is currently used
     */
    public static void ensureEnoughMemoryCapacity(final ByteArrayPointer pointer,
                                                  final CapacityRestriction capacityRestriction,
                                                  final Sequence memoryUsed) {
        if ( memoryUsed.get() + pointer.bytesOccupied() > capacityRestriction.getMaxMemorySizeInBytes() )
            throw new SpaceMemoryOverflowException( capacityRestriction.getMaxMemorySizeInBytes(), pointer.getSerializedData() );
    }

    /**
     * ensure that the space buffer does not violate space capacity and can add new byte array pointer.
     * 
     * @param obj
     *            object that needs to be added to the jspace
     * @param capacityRestriction
     *            space capacity restriction
     * @param itemsCount
     *            how many items currently in space
     */
    public static void ensureEnoughCapacity(final Object obj,
                                            final CapacityRestriction capacityRestriction,
                                            final Sequence itemsCount) {
        if ( itemsCount.get() >= capacityRestriction.getMaxElements() )
            throw new SpaceCapacityOverflowException( capacityRestriction.getMaxElements(), obj );
    }

    private JVMUtil() {}
}
