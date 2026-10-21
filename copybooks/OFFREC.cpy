      *****************************************************************
      * OFFREC - TRAFFIC OFFENCE RECORD (25 BYTES)
      * FILE IS SORTED BY OFF-DRV-ID, OFF-DATE
      *****************************************************************
       01  OFF-REC.
           05  OFF-DRV-ID          PIC X(8).
           05  OFF-DATE            PIC 9(8).
           05  OFF-CODE            PIC X(4).
           05  OFF-CLASS           PIC X(2).
               88  OFF-DBL-ELIGIBLE VALUE 'SP' 'MP' 'SB' 'HL'.
           05  OFF-BASE-PTS        PIC 9(2).
           05  OFF-STATUS          PIC X(1).
               88  OFF-NEW         VALUE 'N'.
               88  OFF-POSTED      VALUE 'P'.
