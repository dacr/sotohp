# QUERYING SOTOHP ZIO-LMDB DATABASE

## console session example

zio-lmdb-sql can be directly started on sotohp database files (example using the fat jar).
```
$ java --add-opens java.base/java.nio=ALL-UNNAMED \
       --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
       -jar zio-lmdb-sql.jar sotohp-v2
zio-lmdb-sql  —  databases home: /home/xxxxx/.lmdb
Type SQL, or \h for help. \q to quit.

lmdb(sotohp-v2)> SELECT cameraName, count(*) AS count
> FROM originals
> WHERE length(cameraName) > 0
> GROUP BY cameraName
> HAVING count(*) > 5000
> ORDER BY count DESC;
cameraName               | count
-------------------------+------
Canon/EOS 5D Mark IV     | 42794
Canon/EOS 30D            | 27584
Canon/EOS R50            | 18207
samsung/Galaxy S23 Ultra | 6797
NIKON/E4500              | 6244
(5 rows in 970ms)

lmdb(sotohp-v2)> select count(*) from originals;
count(*)
--------
140205
(1 row in 476ms)

lmdb(sotohp-v2)> select count(*) from persons;
count(*)
--------
281
(1 row in 14ms)

lmdb(sotohp-v2)> \dt
name                 | kind
---------------------+--------
bags                 | Regular
classifications      | Regular
detectedFaceFeatures | Regular
detectedFaces        | Regular
faceFeatures         | Regular
faces                | Regular
keywordRules         | Regular
medias               | Regular
miniatures           | Regular
normalized           | Regular
objects              | Regular
originals            | Regular
owners               | Regular
persons              | Regular
portfolioAssets      | Multi
portfolios           | Regular
states               | Regular
stores               | Regular
(18 rows in 32ms)

lmdb(sotohp-v2)> describe originals
column              | type
--------------------+----------
_key                | lmdb:uuid
id                  | string
storeId             | string
mediaPath           | string
fileSize            | integer
fileLastModified    | string
kind                | integer
cameraShootDateTime | string
cameraName          | string
artistInfo          | string
dimension           | object
orientation         | integer
location            | object
aperture            | number
exposureTime        | object
iso                 | number
focalLength         | number
(17 rows in 13ms)
```

## Non-interactive execution (`--execute`)

Queries can also be run straight from the command line, without entering the interactive shell, with
`--execute` (short `-e`). The database name is still the first argument; the result is written to
stdout (logs go to stderr), so it is pipe- and script-friendly. `--execute` is repeatable, and
`--format` (short `-f`) picks the output format (`table` — the default, `json`, or `csv`).

```
$ java --add-opens java.base/java.nio=ALL-UNNAMED \
       --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
       -jar zio-lmdb-sql.jar sotohp-v2 --execute "select count(*) from medias"
count(*)
--------
140205

# CSV output, piped to a file
$ java ... -jar zio-lmdb-sql.jar sotohp-v2 -f csv \
       -e "select cameraName, count(*) as n from originals group by cameraName" > cameras.csv

# several statements in one run
$ java ... -jar zio-lmdb-sql.jar sotohp-v2 \
       -e "select count(*) as photos from originals" \
       -e "select count(*) as people from persons"
```

A statement that fails prints `error: …` to stderr and the process exits non-zero, so `--execute`
composes cleanly in shell pipelines and `Makefile`s.

## SQL query examples

### used camera information

```
SELECT cameraName, count(*) AS count
FROM originals
WHERE length(cameraName) > 0
GROUP BY cameraName
HAVING count(*) > 500
ORDER BY count DESC;
```

### photos statistics related to time

```
SELECT year(timestamp) AS year, count(*)
FROM medias m
GROUP BY year
```

```
SELECT year(m.timestamp) AS dy, month(m.timestamp) AS dm, count(*)
FROM medias m
GROUP BY dy, dm
ORDER BY dy, dm
```


### playing with GPS data

```
SELECT o.location.altitude AS alt
FROM originals o
WHERE alt IS NOT NULL
ORDER BY alt DESC
LIMIT 10;
```

```
SELECT _key, geo_distance(o.location.latitude, o.location.longitude, 48.8566, 2.3522) AS dist
FROM medias o
WHERE dist <= 50000
ORDER by dist
```

```
SELECT b.name, geo_distance(m.location, 48.8566, 2.3522) AS dist
FROM medias m JOIN bags b ON m.bagId = b.id 
WHERE dist <= 50000
ORDER by dist
```

```
SELECT b.name, geo_distance(m.location, 48.8566, 2.3522) / 1000 AS distKM
FROM medias m JOIN bags b ON m.bagId = b.id
WHERE distKM <= 1
ORDER by distKM;
```

how many photos with unknown location
```
SELECT count(*)
FROM medias m
WHERE m.location IS NULL;
```

### people

```
SELECT p.lastName AS ln, p.firstName AS fn
FROM persons p
ORDER BY ln;
```

### people faces

```
SELECT p.firstName, p.lastName, count(f.faceId) AS n
FROM persons p join detectedFaces f on f.identifiedPersonId = p._key
GROUP BY p.firstName, p.lastName
HAVING count(f.faceId) > 100
ORDER BY n;
```

```
SELECT p.firstName, p.lastName, count(f.faceId) AS n
FROM persons p JOIN detectedFaces f ON f.identifiedPersonId = p._key
GROUP BY p.firstName, p.lastName
HAVING n > 100
ORDER BY n;
```

### faces dataset

Identifed and confirmed faces:
```
SELECT count(*)
FROM detectedFaces f
WHERE f.identifiedPersonId IS NOT NULL;
```

Identifed and confirmed faces grouped by person:
```
SELECT p.lastName, p.firstName, count(*) AS count
FROM persons p JOIN detectedFaces f ON f.identifiedPersonId = p._key
WHERE f.identifiedPersonId IS NOT NULL
GROUP BY p.lastName, p.firstName
HAVING count > 0
ORDER BY count DESC;
```

how many people with more than 100 confirmed identified faces:
```
SELECT p.lastName AS ln, p.firstName AS fn, count(*) AS count
FROM persons p JOIN detectedFaces f ON f.identifiedPersonId = p._key
WHERE f.identifiedPersonId IS NOT NULL
GROUP BY ln, fn
HAVING count > 500
ORDER BY count DESC;
```

Unidentified faces
```
SELECT count(*)
FROM detectedFaces f
WHERE f.identifiedPersonId IS NULL;
```

Inferred but not confirmed faces:
```
SELECT count(*)
FROM detectedFaces f
WHERE f.identifiedPersonId IS NULL
AND f.inferredIdentifiedPersonId IS NOT NULL;
```

## More query features

The queries below exercise the more recently added SQL features. They are all read-only and run
directly against `sotohp-v2`.

### file-size statistics — aggregate expression arguments + ROUND

Aggregates accept an arbitrary expression argument (here a byte→MiB conversion), and the result can
be reshaped with the numeric functions:

```
SELECT round(avg(fileSize) / 1048576, 2) AS avgMiB,
       round(max(fileSize) / 1048576, 1) AS maxMiB,
       count(*) AS n
FROM originals;
```

### camera diversity — COUNT(DISTINCT)

```
SELECT count(distinct cameraName) AS distinctCameras
FROM originals;
```

Distinct cameras used per year (over the medias→originals join):

```
SELECT year(timestamp) AS y, count(*) AS photos, count(distinct o.cameraName) AS cameras
FROM medias m JOIN originals o ON m.originalId = o._key
GROUP BY y
HAVING cameras > 0
ORDER BY y;
```

### resolution buckets — CASE as a GROUP BY bucket

A `CASE` expression buckets each photo by megapixels, and the bucket is grouped by its alias:

```
SELECT CASE WHEN dimension.width * dimension.height >= 20000000 THEN '20MP+'
            WHEN dimension.width * dimension.height >= 10000000 THEN '10-20MP'
            ELSE '<10MP' END AS bucket,
       count(*) AS n
FROM originals
WHERE dimension.width IS NOT NULL
GROUP BY bucket
ORDER BY n DESC;
```

### exposure settings — MOD / ROUND grouping

Most common (rounded) aperture f-stops:

```
SELECT round(aperture, 0) AS fstop, count(*) AS n
FROM originals
WHERE aperture IS NOT NULL
GROUP BY fstop
ORDER BY n DESC;
```

### filtering — IN / BETWEEN / NOT LIKE

```
SELECT count(*) AS canon
FROM originals
WHERE cameraName IN ('Canon/EOS 5D Mark IV', 'Canon/EOS R50');
```

```
SELECT count(*) AS lowIso
FROM originals
WHERE iso BETWEEN 100 AND 800;
```

```
SELECT count(*) AS nonCanonNamed
FROM originals
WHERE cameraName NOT IN ('') AND cameraName NOT LIKE 'Canon%';
```

### labels and null-handling — CAST / CONCAT / NULLIF

Average file size per popular camera, formatted as a label:

```
SELECT cameraName,
       concat(cast(round(avg(fileSize) / 1048576, 1) AS string), ' MiB') AS avgSize
FROM originals
WHERE length(cameraName) > 0
GROUP BY cameraName
HAVING count(*) > 1000
ORDER BY avg(fileSize) DESC;
```

How many originals carry a non-empty camera name (`NULLIF` turns the empty string into `NULL`, which
`count` then skips):

```
SELECT count(nullif(cameraName, '')) AS named, count(*) AS total
FROM originals;
```

### date ranges and roll-ups

Photos within a calendar year, using a plain timestamp-string range:

```
SELECT count(*) AS n
FROM medias
WHERE timestamp >= '2024-01-01' AND timestamp < '2025-01-01';
```

Per-year counts restricted to a span of years with `BETWEEN` on the extracted year:

```
SELECT year(timestamp) AS y, count(*) AS n
FROM medias
WHERE year(timestamp) BETWEEN 2018 AND 2020
GROUP BY y
ORDER BY y;
```

## Index-served queries (zio-lmdb ≥ 3.0.0-RC3)

Since the indexes are attached with `withDeclaredIndex`, their mappings are persisted and the SQL
engine plans `WHERE`/`ORDER BY` against them instead of scanning the collection. `SHOW INDEXES`
shows which fields each index serves:

```
lmdb(sotohp-v2)> SHOW INDEXES;
name                  | source        | on
----------------------+---------------+---------------------------------------------------------
faceIdByPersonId      | detectedFaces | coalesce(identifiedPersonId, inferredIdentifiedPersonId)
originalIdByBagId     | medias        | bagId
originalIdByLocation  |               |
originalIdByPosition  |               |
originalIdByStoreId   | originals     | storeId
originalIdByTimestamp | medias        | timestamp, _key
```

`EXPLAIN <select>` shows the access path the planner picks. Only `column <op> literal` conjuncts
combined with `AND` are pushable — an `OR` or a function call falls back to a full scan.

### photos of a time range, in chronological order

`timestamp BETWEEN … AND …` plus `ORDER BY timestamp` rides `originalIdByTimestamp`: the scan is a
byte range over the index and already sorted, so `LIMIT n` touches ~n index entries instead of the
whole 140k-media collection (~0.1s instead of ~2s):

```
lmdb(sotohp-v2)> EXPLAIN SELECT _key, timestamp FROM medias
> WHERE timestamp BETWEEN '2024-06-01' AND '2024-07-01'
> ORDER BY timestamp LIMIT 5;
step   | detail
-------+-----------------------------------------------------------------------------
access | index range scan on originalIdByTimestamp [range(timestamp between) ordered]
lookup | fetch matching records from medias by primary key
order  | scan order matches ORDER BY (no sort)
filter | none

lmdb(sotohp-v2)> SELECT _key, timestamp FROM medias
> WHERE timestamp BETWEEN '2024-06-01' AND '2024-07-01'
> ORDER BY timestamp LIMIT 5;
_key                                 | timestamp
-------------------------------------+---------------------
730d7f9d-f269-5fe4-affe-906af212c03a | 2024-06-01T05:42:05Z
cf366e39-0d21-511a-81b1-e0e336b0c5c1 | 2024-06-01T05:43:01Z
54c01411-1edd-57be-bd98-472591f899fa | 2024-06-01T05:56:05Z
d417f9bf-b2f6-5185-b6fe-c5bcba75fb1f | 2024-06-01T05:58:40Z
e38e16b2-cdc7-5eaa-a3ae-17b8cbcbb146 | 2024-06-01T05:59:26Z
```

### all faces of a person

`faceIdByPersonId` is declared on `coalesce(identifiedPersonId, inferredIdentifiedPersonId)`, so an
equality on `identifiedPersonId` (the first coalesced field) is served by the index; the predicate
is re-checked per row because the coalesce scan is a superset (it also indexes inferred-only faces):

```
lmdb(sotohp-v2)> EXPLAIN SELECT count(*) AS faces FROM detectedFaces
> WHERE identifiedPersonId = '01K9HE6YV4F2M0ZBYQHCCV9BVR';
step   | detail
-------+--------------------------------------------------------------
access | index range scan on faceIdByPersonId [eq(identifiedPersonId)]
lookup | fetch matching records from detectedFaces by primary key
filter | identifiedPersonId = '01K9HE6YV4F2M0ZBYQHCCV9BVR'

lmdb(sotohp-v2)> SELECT count(*) AS faces FROM detectedFaces
> WHERE identifiedPersonId = '01K9HE6YV4F2M0ZBYQHCCV9BVR';
faces
-----
3821
```

The index entries are `(timestamp, faceId)` pairs, so the same index also answers "a person's faces
in chronological order" without sorting:

```
SELECT _key, timestamp FROM detectedFaces
WHERE identifiedPersonId = '01K9HE6YV4F2M0ZBYQHCCV9BVR'
ORDER BY timestamp LIMIT 20;
```

### originals of a store

```
lmdb(sotohp-v2)> SELECT count(*) AS n FROM originals
> WHERE storeId = '3c98c69c-0db6-4dcf-8d25-fddabe726689';
n
-----
15873
```

(`EXPLAIN` shows `index range scan on originalIdByStoreId [eq(storeId)]`.)

### content of a bag, in chronological order

`originalIdByBagId` maps a bag to its medias as `(timestamp, originalId)` pairs, so listing a bag
chronologically is a single index-key scan with no sort:

```
SELECT _key, timestamp FROM medias
WHERE bagId = 'c6a9333e-457d-4fa4-a66f-3daf7f9683fc'
ORDER BY timestamp LIMIT 5;
```

> **Warning — stale index**: index-served queries are only as correct as the index contents. As of
> 2026-07-17 `originalIdByBagId` holds 10 219 entries while 150 295 medias carry a `bagId` (bulk bag
> assignment bypassed the index updaters), so bag queries through the index return incomplete
> results. Run `collectionMedias.rebuildIndexes()` once (same pattern as the positional-index
> backfill) to resynchronize. Also remember that SQL `INSERT`/`UPDATE`/`DELETE` do not maintain
> indexes — mutate through the application.
