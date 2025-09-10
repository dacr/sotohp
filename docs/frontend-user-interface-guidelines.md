# frontend user interface guidelines

## goals

- create a front end user interface for the SotoHP project
    - follow the available features in the OPENAPI REST API specification provided by this project
- the default view should show a random image, updated every 5 seconds
- provides configuration pages to manage owners, stores and events

## coding choices

- all the source files are in the `frontend-user-interface` folder`
- build the front end user interface sources into the `frontend-user-interface-dist` folder
- use typescript programming language with the angular framework
  - all backend features are served by the OpenAPI backed provided by `user-interfaces/api`
  - download the OpenAPI spec from `http://127.0.0.1:8080/docs/docs.yaml` using `curl`
- ONLY LOOK TO THE API SPECS TO DESIGN THE FRONT END USER INTERFACE
  - ignore all the scala code
- use the global makefile to build the front end user interface sources
