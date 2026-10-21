      *****************************************************************
      * DRVMAST - DRIVER MASTER RECORD (60 BYTES)
      *****************************************************************
       01  DRV-REC.
           05  DRV-ID              PIC X(8).
           05  DRV-NAME            PIC X(20).
           05  DRV-DOB             PIC 9(8).
           05  DRV-LIC-CLASS       PIC X(2).
               88  DRV-LEARNER     VALUE 'LN'.
               88  DRV-P1          VALUE 'P1'.
               88  DRV-P2          VALUE 'P2'.
               88  DRV-OPEN        VALUE 'OP'.
           05  DRV-CLASS-DATE      PIC 9(8).
           05  DRV-P1-DATE         PIC 9(8).
           05  DRV-DMR-PTS         PIC 9(2).
           05  DRV-SUSP-DAYS       PIC 9(4).
