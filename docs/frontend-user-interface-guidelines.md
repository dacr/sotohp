# frontend user interface guidelines

## goals

- create a front end user interface for the SotoHP project
    - follow the available features in the OPENAPI REST API specification provided by this project
- organize features through tabs
  - image visualization tab
    - maximize the space for the image
    - display image information (date, keywords, first event name, starred indicator, hasLocation indicator)
    - add a set of control buttons (first, previous, next, last, random, play/pause slideshow, delay choices, fullscreen)
  - world (openstreetmap) zoomable tab
    - use zoom-in and zoom-out to see the photo distribution all other the world
    - use a clustered approach as thousands of photos can be displayed
      - filter media that has a known location ignore all others
    - when a photo is selected, popup an information zone with
      - photo information (date, first event name, starred indicator)
      - the photo itself in a reduced format 
  - events management tab
    - list events
  - owners management tab
    - list owners
  - stores management tab
    - list stores
    - when creating a new store, choose the owner between already available owners
  - settings tab
    - add a synchronize button to force the synchronization of all defined stores

## coding choices

- all backend features are served by the already provided OpenAPI backend
  - download the OpenAPI spec from `http://127.0.0.1:8080/docs/docs.yaml` using `curl`
  - the backend implements the openapi through the `/api` relative path
  - the backend serves all assets (images, css, js, svg) through the `/assets` relative path
  - the backend provides routing fallbacks to `/ui/` (with the same `index.html` content) for all other paths dedicated to the front end user interface
- ONLY LOOK TO THE API SPECS TO DESIGN THE FRONT END USER INTERFACE
    - ignore all the scala code, do not modify any scala files when working on the front end user interface
    - if something is missing ask me
- all the source files are in the `frontend-user-interface` folder`
- build the front end user interface sources into the `frontend-user-interface-dist` folder
- use **typescript** programming language associated and those kind of libraries/frameworks
  - angular framework
  - axios openapi client
- use the global makefile to build the front end user interface sources
