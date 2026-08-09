# SageTV-NG Docker hot-swap deploy

How to update a running **state-managed `sagetv-ng` container** to the latest
SageTV-NG build without rebuilding the base image. Script:
[`build/deploy_jar_ng.sh`](../build/deploy_jar_ng.sh).

## The one rule: ship `Sage.jar` **and** `JARs/` together

The container runs java with `-cp Sage.jar:JARs/*`. A `Sage.jar`-only hot-swap
is **unsafe**. Whenever `build.gradle` dependencies change, the new `Sage.jar`
references classes that the frozen `JARs/` directory does not contain.

Concrete failure we hit: `main-NG` bumped `jcifs` from legacy `1.1.6` to
`org.codelibs:jcifs:2.1.37` (jcifs-ng). `sage.api.Configuration` then references
`jcifs.CIFSContext`, `jcifs.context.SingletonContext`,
`jcifs.smb.NtlmPasswordAuthenticator`, etc. `sage.Catbert`'s static initializer
reflectively scans `sage.api.*`, so a single missing class becomes
`ExceptionInInitializerError` → a flood of
`NoClassDefFoundError: Could not initialize class sage.Catbert`. **The server
process starts but never binds its ports** (7818 / 31099 / 42024) — it looks
"up" to `docker ps` but clients cannot connect.

**Always deploy the refreshed `JARs/` alongside `Sage.jar`, and gate on
`jdeps --missing-deps` (empty output = safe).**

## Build the artifacts

From the repo root:

```sh
./gradlew sageJar copyRuntimeJars      # tests run by default; add -x test if the
                                       # unrelated ClientProfileTest fails locally
cd build && sh copyserverfiles.sh      # assembles build/serverrelease/{Sage.jar,JARs/}
```

`copyserverfiles.sh` composes the authoritative `JARs/` set:
`third_party/{Oracle,Apache,Lucene}/*.jar` + `buildoutput/runtime-jars/*.jar`.
It deliberately does **not** carry the legacy `third_party/JCIFS/jcifs-1.1.6.jar`,
so a full `JARs/` replace correctly drops the conflicting old jar.

> Build quirks: Gradle's `buildDir` is overridden to `buildoutput/` (so
> `gradle clean` won't nuke the legacy `build/` tree), but the `sageJar` task
> still writes to `build/release/Sage.jar`. Use a JDK 21 that matches the
> container's OpenJDK 21.

## Deploy

Copy `build/serverrelease/` to the docker host, then run the script **there**:

```sh
STAGE=/path/to/serverrelease CONTAINER=sagetv-ng ./deploy_jar_ng.sh
```

The script: runs the `jdeps` preflight and aborts on any missing class; backs up
`Sage.jar` + `JARs/` to `*.bak-<timestamp>`; installs the new jar and `JARs/`;
stamps `DEPLOYED_COMMIT`; restarts; and fails loudly if Catbert init errors
appear in the log.

## Restart mechanism (do NOT use `startsage`)

The state-managed `entrypoint-state.sh` runs java as its child (PPID = the
entrypoint) and **respawns it in-place** whenever it exits, as long as the
`.sage-run` RUNFILE exists. The correct restart is therefore:

```sh
# SIGTERM the supervised java; the entrypoint respawns it with the on-disk jar
docker exec sagetv-ng bash -c 'kill -TERM "$(ps -o pid,ppid -C java | awk "\$2==7{print \$1}")"'
```

Do **not** use the container's `./stopsage` / `./startsage`: they hit
`/var/run/sagetv.pid` permission errors and can spawn a **second, unsupervised**
java with a wrong legacy classpath.

## Verify

```sh
docker exec sagetv-ng bash -c 'ss -lnt | grep -E ":(7818|31099|42024) "'   # all three OPEN
docker exec sagetv-ng bash -c 'tail -n 400 /opt/sagetv/server/sagetv_0.txt \
  | grep -cE "Could not initialize class sage.Catbert|ExceptionInInitializer"'  # must be 0
docker inspect -f "{{.RestartCount}}" sagetv-ng                              # 0
```

Port checks are most reliable **from the docker host** (host networking) or via
a `/dev/tcp` connect test; `ss` run inside the container can under-report.
Benign optional-plugin `ClassNotFoundException`s (PhoenixPlugin, TVBrowser,
JettyPlugin, …) are expected at boot and are unrelated to Catbert.

## Rollback

```sh
SRV=/opt/sagetv/server; B=<timestamp>
docker exec sagetv-ng bash -c "rm -f $SRV/JARs/*.jar; cp -a $SRV/JARs.bak-$B/*.jar $SRV/JARs/; \
  cp -a $SRV/Sage.jar.bak-$B $SRV/Sage.jar"
# then SIGTERM the supervised java (above) to respawn on the restored files
```

Restore **both** `JARs/` and `Sage.jar` — a jar-only rollback re-introduces the
same mismatch.

## Scope

This procedure only touches the container. The physical host's
`sagetv.service` / `opendct.service` are not involved (they are inactive on the
containerized box).
