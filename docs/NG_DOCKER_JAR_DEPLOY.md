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

### State-managed Java 21 host layout

On the state-managed production host, the authoritative host-side Java 21
payload is:

```text
/opt/sagetv/jars/java21/Sage.jar
/opt/sagetv/jars/java21/JARs/
```

The entrypoint materializes that payload inside the container at
`/opt/sagetv/server/`, where the process runs with CWD `/opt/sagetv/server` and
classpath `Sage.jar:JARs/*`. The host path `/opt/sagetv/server/Sage.jar` is a
legacy artifact and is **not** the active source for this deployment.

For this layout, stage and atomically replace the Java 21 payload under
`/opt/sagetv/jars/java21/`, preserving the `Sage.jar` + `JARs/` backup/rollback
pair, then restart the Sage container once so the entrypoint materializes it.
Do not infer the active artifact from the same-looking host
`/opt/sagetv/server/Sage.jar`.

After restart, verify the running process and the materialized jar through its
mount namespace:

```sh
pid="$(pgrep -f 'sage\.Sage')"
tr '\0' ' ' < "/proc/$pid/cmdline"       # must show -cp Sage.jar:JARs/*
readlink "/proc/$pid/cwd"                # /opt/sagetv/server
sha256sum "/proc/$pid/root/opt/sagetv/server/Sage.jar"
sha256sum /opt/sagetv/jars/java21/Sage.jar
```

The two SHA-256 values must match. This check avoids confusing the inactive
host `/opt/sagetv/server/Sage.jar` with the container's active materialized jar.

### GPU broker / warm-resident VSR rollout order

For the coordinated GPU broker, core, and VSR plugin release, use this order:

1. Deploy broker commit `e58` first, with the warm-resident policy still
   disabled.
2. Disable the legacy adapter so there is only one authority and lease owner:
   edit only `/opt/sagetv/state/mine/Sage.properties` and remove the exact
   comma-list member `deploy.gpu.broker.GpuBrokerBootstrap` from
   `load_at_startup_runnable_classes`, preserving every other member and its
   order. Do not edit `/opt/sagetv/server/Sage.properties`. Move (do not delete)
   `/opt/sagetv/jars/java21/gpu-broker-adapter.jar` outside the `JARs/*`
   classpath, for example to
   `/opt/sagetv/state/mine/disabled-jars/gpu-broker-adapter.jar.pre-warm-resident`,
   preserving its metadata and hash.
   The final Python worker has no broker client/imports, broker URL/token
   command-line options, or disable key; broker ownership is structural in the
   plugin Java. However, the production wrapper
   `/media/sagetv/nextcloud/SageTV9/vsr-runtime/run_worker.sh` previously
   exported `GPU_ROUTER_ENABLED`, `GPU_ROUTER_URL`,
   `GPU_ROUTER_APPLICATION_ID`, and `GPU_ROUTER_TOKEN` and read
   `.gpu_router_token`. Install the sanitized replacement wrapper in this same
   maintenance window; it must unset every `GPU_ROUTER_*` and
   `SAGETV_VSR_BROKER_*` variable before executing the worker. Also confirm
   `vsr/worker_extra_args` contains no legacy broker flags.
3. Stage core commit `e1926596` and the matching final VSR plugin together. The
   active plugin destination is
   `/opt/sagetv/jars/java21/sagetv-ng-vsr-plugin.jar`; its configured
   `worker_path` must remain the sanitized wrapper path above.
   Install the complete matching `Sage.jar` + `JARs/` overlay under the
   authoritative host path `/opt/sagetv/jars/java21/`.
4. Restart Sage exactly once so the state-managed entrypoint materializes the
   staged payload.
5. Verify the running command, ports, logs, and the materialized core hash:

   ```sh
   pid="$(pgrep -f 'sage\.Sage')"
   tr '\0' ' ' < "/proc/$pid/cmdline"  # must show -cp Sage.jar:JARs/*
   sha256sum /opt/sagetv/jars/java21/Sage.jar
   sha256sum "/proc/$pid/root/opt/sagetv/server/Sage.jar"
   ```

   The host and `/proc/$pid/root` hashes must be identical before proceeding.
6. Only after verification, enable the broker's 3072 MiB warm-resident policy.

Pinned staged artifacts for this rollout:

| Component | Commit | SHA-256 |
|---|---|---|
| SageTV-NG core `Sage.jar` | `e1926596` | `9d44b16beafefc7dc3fb36cb5ce5bbda4ab251e957b23f19093cee07499611e2` |
| VSR plugin JAR | `4263af7` (launcher mode fix `a3f7d28`) | `e5ce77c1dd56d7918ce79262f9931ef6f9f341e7e69c854cc44a896551fe49f4` |
| Sanitized `run_worker.sh` | `a3f7d28` | `e881da9968eb6cee64af6cac5a71798133541d12bb5105b0928d5592874b474c` |

Verify these hashes before replacing any active file. The launcher must retain
its executable bit.

For rollback, first disable and unload the warm-resident policy so no lease or
resident worker depends on the new clients. Then restore the previous plugin
and the backed-up `/opt/sagetv/jars/java21/{Sage.jar,JARs/}` pair, restart Sage
once, and repeat the `/proc/$pid/root` hash and health checks. Never restore the
JARs while the new policy remains active.

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
