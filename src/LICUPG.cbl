      *****************************************************************
      * PROGRAM   : LICUPG
      * SYSTEM    : LICSYS - DRIVER LICENSING NIGHTLY BATCH
      * PURPOSE   : PROGRESS PROVISIONAL DRIVERS TO THE NEXT LICENCE
      *             CLASS WHEN THEY ARE ELIGIBLE ON THE RUN DATE.
      * HISTORY   :
      *   1994-05-19 RKW  INITIAL VERSION (PROVISIONAL TO OPEN)
      *   1999-11-18 TLM  Y2K - 8 DIGIT DATES
      *   2007-07-01 SDM  GRADUATED LICENSING - P1/P2 SPLIT (CR 3175)
      *   2011-03-14 SDM  EXCLUDE SUSPENDED DAYS FROM TENURE (CR 3902)
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LICUPG.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT RUNDATE-IN  ASSIGN TO "data/RUNDATE.dat"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT DRVMAST-IN  ASSIGN TO "out/DRVMAST.posted"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT DRVMAST-OUT ASSIGN TO "out/DRVMAST.new"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT UPGRADE-RPT ASSIGN TO "out/UPGRADES.rpt"
               ORGANIZATION IS LINE SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  RUNDATE-IN.
       01  RUNDATE-IN-REC          PIC X(8).
       FD  DRVMAST-IN.
       01  DRVMAST-IN-REC          PIC X(60).
       FD  DRVMAST-OUT.
       01  DRVMAST-OUT-REC         PIC X(60).
       FD  UPGRADE-RPT.
       01  UPGRADE-LINE            PIC X(80).
       WORKING-STORAGE SECTION.
           COPY DRVMAST.
       01  WS-RUN-DATE             PIC 9(8).
       01  WS-EOF                  PIC X VALUE 'N'.
           88  EOF-DRV             VALUE 'Y'.
       01  WS-TENURE-DAYS          PIC S9(7).
       01  WS-OLD-CLASS            PIC X(2).
       01  WS-NEW-CLASS            PIC X(2).
       01  WS-READ-COUNT           PIC 9(5) VALUE 0.
       01  WS-UPGRADE-COUNT        PIC 9(5) VALUE 0.
       01  WS-UPG-HEADER.
           05  FILLER              PIC X(40)
               VALUE 'DRIVER    NAME                  FROM  TO'.
           05  FILLER              PIC X(40)
               VALUE '   TENURE  EFFECTIVE'.
       01  WS-UPG-DETAIL.
           05  UD-DRV-ID           PIC X(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  UD-NAME             PIC X(20).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  UD-FROM             PIC X(2).
           05  FILLER              PIC X(4) VALUE SPACES.
           05  UD-TO               PIC X(2).
           05  FILLER              PIC X(3) VALUE SPACES.
           05  UD-TENURE           PIC ZZZZ9.
           05  FILLER              PIC X(3) VALUE SPACES.
           05  UD-EFFECTIVE        PIC 9(8).
       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT RUNDATE-IN
           READ RUNDATE-IN INTO WS-RUN-DATE
           CLOSE RUNDATE-IN
           OPEN INPUT  DRVMAST-IN
           OPEN OUTPUT DRVMAST-OUT UPGRADE-RPT
           WRITE UPGRADE-LINE FROM WS-UPG-HEADER
           PERFORM UNTIL EOF-DRV
               READ DRVMAST-IN INTO DRV-REC
                   AT END
                       SET EOF-DRV TO TRUE
                   NOT AT END
                       ADD 1 TO WS-READ-COUNT
                       PERFORM 2000-EVALUATE-PROGRESSION
                       WRITE DRVMAST-OUT-REC FROM DRV-REC
               END-READ
           END-PERFORM
           CLOSE DRVMAST-IN DRVMAST-OUT UPGRADE-RPT
           DISPLAY 'LICUPG  RUN DATE        : ' WS-RUN-DATE
           DISPLAY 'LICUPG  DRIVERS READ    : ' WS-READ-COUNT
           DISPLAY 'LICUPG  DRIVERS UPGRADED: ' WS-UPGRADE-COUNT
           STOP RUN.

       2000-EVALUATE-PROGRESSION.
           EVALUATE TRUE
               WHEN DRV-P1
                   COMPUTE WS-TENURE-DAYS =
                       FUNCTION INTEGER-OF-DATE(WS-RUN-DATE)
                     - FUNCTION INTEGER-OF-DATE(DRV-CLASS-DATE)
                     - DRV-SUSP-DAYS
                   IF WS-TENURE-DAYS >= 365 AND DRV-DMR-PTS < 4
                       MOVE 'P2' TO WS-NEW-CLASS
                       PERFORM 2100-UPGRADE
                   END-IF
               WHEN DRV-P2
                   COMPUTE WS-TENURE-DAYS =
                       FUNCTION INTEGER-OF-DATE(WS-RUN-DATE)
                     - FUNCTION INTEGER-OF-DATE(DRV-P1-DATE)
                     - DRV-SUSP-DAYS
                   IF WS-TENURE-DAYS >= 730 AND DRV-DMR-PTS < 4
                       MOVE 'OP' TO WS-NEW-CLASS
                       PERFORM 2100-UPGRADE
                   END-IF
               WHEN OTHER
                   CONTINUE
           END-EVALUATE.

       2100-UPGRADE.
           MOVE DRV-LIC-CLASS  TO WS-OLD-CLASS
           MOVE DRV-ID         TO UD-DRV-ID
           MOVE DRV-NAME       TO UD-NAME
           MOVE WS-OLD-CLASS   TO UD-FROM
           MOVE WS-NEW-CLASS   TO UD-TO
           MOVE WS-TENURE-DAYS TO UD-TENURE
           MOVE WS-RUN-DATE    TO UD-EFFECTIVE
           WRITE UPGRADE-LINE FROM WS-UPG-DETAIL
           MOVE WS-NEW-CLASS   TO DRV-LIC-CLASS
           MOVE WS-RUN-DATE    TO DRV-CLASS-DATE
           MOVE 0              TO DRV-SUSP-DAYS
           ADD 1 TO WS-UPGRADE-COUNT.
