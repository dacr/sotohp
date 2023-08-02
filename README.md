# SOTOHP

A software to easily manage a huge amount of photos. Why ? Just because those past 22 years, I've taken more than 89000
photos, and more than 4600 videos. Once again this project has first started as just [a "small" script][photosc].

## Principles

1. The photos referential is always your photos directories as you've organized them,
2. Your photos directories are left unchanged, only read operations are done,
3. A local LMDB database is used to store/cache, serialization is JSON based,
4. Background jobs are run to update, to enrich, to analyze, to process your photos

## TODO

- [ ] enhanced photo classification
- [ ] [instance segmentation](instseg)
- [ ] text in photos extraction
- [ ] faces detection/extraction
- [ ] faces clustering

[photosc]: https://gist.github.com/dacr/46718666ae96ebac300b27c80ed7bec3
[instseg]: https://www.reasonfieldlab.com/post/instance-segmentation-algorithms-overview