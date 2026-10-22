# LICSYS

> **Demo codebase.** Fictional system written by Cognition to demonstrate Devin. It is not code from any government agency, and the business rules are simplified and invented for the demo.

Driver licensing nightly batch.

    ./build.sh          # needs GnuCOBOL 3.x  (apt-get install gnucobol)
    ./run_nightly.sh    # reads data/, writes out/
    tests/run_tests.sh  # regression tests (see tests/README.md)

Ops contact: batch desk ext 4471

Operator console (driver enquiry, F5 runs the batch):

    python3 console/server.py 8080   # http://localhost:8080
