# Dataflow Agent

A Java agent that records a dynamic call graph of the chosen methods.


## Build

```sh
./gradlew build
```
The jar is generated in `build/libs/`.


## Usage

```sh
java -javaagent:path/to/flow-agent.jar=prefix=<prefixList>,out=</path/to/file> \
     -jar your-application.jar
```

- `prefix` takes a `+`-separated list of prefixes matching fully qualified class names. Some examples:
  - match all classes from the JaCoCo project: `org.jacoco.`
  - match all classes in the `fr.inria` AND `org.moosetechnology` packages: `fr.inria.+org.moosetechnology.`
