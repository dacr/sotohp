# SOTOHP

A software to easily manage a huge amount of photos. Why ? Just because those past 23 years, I've taken more than 90000
photos, and more than 4600 videos. Once again this project has first started as just [a "small" script][photosc].

## Principles

- The photos referential is always your photos directories as you've organized them,
- Your photos directories are left unchanged, only read operations are done,
- A local LMDB database is used to store/cache, serialization is JSON based,
- Background jobs are run to update, to enrich, to analyze, to process your photos
- Dedicated Filesystem targets for pre-processed photos 
- Photos are identified using an [ULID][ulid] identifier computed from the shoot timestamp
  - if shoot timestamp is missing or invalid, file last modified is used (at first seen)
  - SO photos stream is automatically ordered by this timestamp   

[photosc]: https://gist.github.com/dacr/46718666ae96ebac300b27c80ed7bec3
[ulid]: https://github.com/ulid/spec
