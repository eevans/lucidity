package com.opennms.cassandra.lucidity;


import javax.persistence.Entity;
import javax.persistence.Id;

import org.junit.Test;

import com.opennms.cassandra.lucidity.Schema;


public class SchemaTest {

    @Entity private class InvalidConstructor {
        @SuppressWarnings("unused")
        InvalidConstructor(String argument) {

        }
    }

    @Entity private class InvalidID {
        @Id private String id;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNoArgConstructor() {
        Schema.fromClass(InvalidConstructor.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIDType() {
        Schema.fromClass(InvalidID.class);
    }

}