Uppend: an append-only, key-multivalue store
============================================
[![Build Status](https://img.shields.io/travis/upserve/uppend/master.svg?style=flat-square)](https://travis-ci.org/upserve/uppend)
[![Release Artifact](https://img.shields.io/maven-central/v/com.upserve/uppend.svg?style=flat-square)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.upserve%22%20AND%20a%3Auppend)
[![Test Coverage](https://img.shields.io/codecov/c/github/upserve/uppend/master.svg?style=flat-square)](https://codecov.io/github/upserve/uppend?branch=master)

Uppend is an append-only, key-multivalue store which is suitable for streaming
event aggregation. It assumes a single writer process, and appended values are
immutable.

Use
---

Maven:

```xml
<dependency>
    <groupId>com.upserve</groupId>
    <artifactId>uppend</artifactId>
    <version>0.0.1</version>
</dependency>
```

Gradle:
```gradle
compile 'com.upserve:uppend:0.0.1'
```

Hello world:

```java
AppendOnlyStore db = Uppend.store("build/tmp-db").build();

db.append("my-appendStorePartition", "my-key", "value-1".getBytes());
db.append("my-appendStorePartition", "my-key", "value-2".getBytes());

String values = db.readSequential("my-appendStorePartition", "my-key")
        .map(String::new)
        .collect(Collectors.joining(", "));
// value-1, value-2
```

Development
-----------

To build Uppend, run:

```sh
./gradlew build
```

To benchmark Uppend:

```sh
./gradlew clean fatJar
java -jar build/libs/uppend-all-*.jar benchmark --help
```
