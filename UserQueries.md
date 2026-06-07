# QUERYING SOTOHP ZIO-LMDB DATABASE
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


```
SELECT p.firstName, p.lastName, count(f.faceId) AS n
FROM persons p join detectedFaces f on f.identifiedPersonId = p._key
GROUP BY p.firstName, p.lastName
HAVING count(f.faceId) > 100
ORDER BY n;
```

```
SELECT cameraName, count(*) AS count
FROM originals
WHERE length(cameraName) > 0
GROUP BY cameraName
HAVING count(*) > 500
ORDER BY count DESC;
```


```
select count(*)
from detectedFaces;
```

```
select count(*)
from persons;
```

```
select count(*)
from originals;
```
