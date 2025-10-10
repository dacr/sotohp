# SOTOHP [![][sotohpImg]][sotohpLnk]

A software to easily and quickly manage a huge amount of photos. Why ? Just because those past 123 years 😉 we have to deal with almost **140,000 photos** through 2,000 family events.

Once again this project has first started as just [a "small" script][photosc].

## Principles

- The photo referential is always your photos directories as you've organized them,
- Your photos directories are left unchanged, only read operations are done,
- Cache is used for pre-computed photos, miniatures, people faces, detected objects, ...
- A simple database is used to store all your added metadata such as description, keywords, fixed location, fixed dates, stars, ... 

## Current status
- will be released soon as a standalone docker application,
- comes with an API and a **web user interface** providing:
  - fast visualization,
  - automatic slideshow,
  - timeline mosaic browsing,
  - managing locations, dates, keywords, descriptions, ...
  - managing events,
  - managing several owners and storage directories.

The web user interface is quite fast, user-friendly and feature-rich:
![](docs/screenshots/01-viewer.png)  
![](docs/screenshots/02-mosaic.png)  
![](docs/screenshots/03-events.png)  
![](docs/screenshots/04-maps.png)  

[photosc]: https://gist.github.com/dacr/46718666ae96ebac300b27c80ed7bec3
[lmdb]: https://github.com/dacr/zio-lmdb

[sotohp]:    https://github.com/dacr/sotohp
[sotohpImg]: https://img.shields.io/maven-central/v/fr.janalyse/sotohp-model_3.svg
[sotohpLnk]: https://mvnrepository.com/artifact/fr.janalyse/sotohp-model
