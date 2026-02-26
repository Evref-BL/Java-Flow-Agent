# Flow Agent

A Java agent that records a dynamic call tree of the chosen methods.

When attached to a JVM, the Flow Agent:
- Instruments classes whose fully qualified names match configured prefixes
- Records method entry and exit events
- Writes one call tree file per thread
- Generates a method ID mapping file (ids.properties)

Two output formats are supported:
- binary: compact .flow files
- jsonl: human-readable .jsonl files (JSON Lines, each line contains a JSON value)

The binary format is significantly smaller and recommended for large call trees.

---

## Build

```sh
./gradlew build
```
The jar is generated in `build/libs/`.

---

## Usage

Attach the agent using the `-javaagent` option and provide arguments as comma-separated `key=value` pairs:

```sh
java -javaagent:path/to/flow-agent.jar=target=<prefix[+prefix...]>,out=<dir>[,format=binary|jsonl][,optimize=<dir>][,ids=<file>] \
     -jar your-application.jar
```

### Arguments

* **`target`** (required)
  A `+`-separated list of fully qualified class name prefixes to instrument.
  Only methods in classes whose names start with one of these prefixes will be recorded.

* **`out`** (required)
  Output directory where the agent will write:
  * `ids.properties`, the method ID mapping
  * One call tree file per thread, where invoked methods are referenced by ID

* **`format`** (optional)
  Output format for call tree files:
  * `binary` (default): compact and recommended
  * `jsonl`: human-readable JSON Lines

* **`optimize`** (optional)
  Path to an existing flow output directory.
  The agent will analyze previous trace data to generate an optimized method ID mapping.

* **`ids`** (optional)
  Path to an existing ID mapping file to reuse.

### Examples

Record calls using the default (binary) format:
```sh
java -javaagent:flow-agent.jar=target=com.myapp.,out=/tmp/flow/ \
     -jar myapp.jar
```

Instrument multiple packages:
```sh
java -javaagent:flow-agent.jar=target=com.myapp.service+com.myapp.utils.+org.lib.,out=/tmp/flow/ \
     -jar myapp.jar
```

### Optimization Workflow

When using the binary format, method IDs are encoded using a compact variable-length representation.
Smaller method IDs produce smaller trace files.
The agent can optimize method IDs based on observed call frequencies.

#### Why optimize?

* Frequently called methods receive smaller numeric IDs.
* Smaller IDs result in smaller files.
* Optimization can reduce storage size significantly in large call trees.

Optimization is useful in two scenarios:

1. **Minimizing a follow-up run of the same scenario**  
   If you perform an initial trace to observe method usage, you can optimize the method IDs and then run the application a second time.
   The second run will produce a smaller trace file, which is more efficient to store and process.

2. **Best-effort optimization for similar runs**  
   Even if the next run differs, previous runs can serve as a prediction of typical method usage.
   If the earlier run is representative, the optimized mapping will still reduce trace size.

#### How to optimize?

**Step 1**: Generate baseline trace
```sh
java -javaagent:flow-agent.jar=target=com.myapp.,out=/tmp/flow/ \
     -jar myapp.jar
```

This produces:
```
/tmp/flow/
    ids.properties
    <thread>.flow
```

**Step 2**: Generate optimized mapping

```sh
java -javaagent:flow-agent.jar=target=com.myapp.,optimize=/tmp/flow/,out=/tmp/optimized-flow/ \
     -jar myapp.jar
```

The agent will:
* Scan files from `/tmp/flow/`
* Count how often each method was called
* Assign smaller IDs to more frequently called methods
* Write a new optimized ID mapping in `/tmp/optimized-flow/`

**Step 3**: Reuse optimized IDs

```sh
java -javaagent:flow-agent.jar=target=com.myapp.,ids=/tmp/optimized-flow/ids.properties,out=/tmp/flow-2/ \
     -jar myapp.jar
```

Subsequent runs will produce smaller call tree files.
