# LICSYS

> **Demo codebase.** Fictional system written by Cognition to demonstrate Devin. It is not code from any government agency, and the business rules are simplified and invented for the demo.

Driver licensing nightly batch.

    ./build.sh          # needs GnuCOBOL 3.x  (apt-get install gnucobol)
    ./run_nightly.sh    # reads data/, writes out/
    python3 console/server.py 8080   # operator console: driver enquiry at http://localhost:8080 (F5 runs the batch)

Ops contact: batch desk ext 4471
