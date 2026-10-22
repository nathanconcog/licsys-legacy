      *****************************************************************
      * PROGRAM   : DMRPTS
      * SYSTEM    : LICSYS - DRIVER LICENSING NIGHTLY BATCH
      * PURPOSE   : POST NEW OFFENCES TO THE DRIVER MASTER, APPLY
      *             DOUBLE DEMERITS FOR REPEAT OFFENCES AND LIST
      *             DRIVERS AT OR OVER THEIR DEMERIT THRESHOLD.
      * HISTORY   :
      *   1994-03-02 RKW  INITIAL VERSION
      *   1999-11-18 TLM  Y2K - 8 DIGIT DATES
      *   2004-07-01 PJH  REPEAT OFFENCE DOUBLE DEMERITS (CR 2291)
      *   2013-02-11 ANB  ADDED MOBILE PHONE CLASS MP (CR 4410)
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DMRPTS.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DRVMAST-IN  ASSIGN TO "data/DRVMAST.dat"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT OFFENCE-IN  ASSIGN TO "data/OFFENCES.dat"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT DRVMAST-OUT ASSIGN TO "out/DRVMAST.posted"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT POSTING-RPT ASSIGN TO "out/POSTINGS.rpt"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT NOTICE-RPT  ASSIGN TO "out/NOTICES.rpt"
               ORGANIZATION IS LINE SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  DRVMAST-IN.
       01  DRVMAST-IN-REC          PIC X(60).
       FD  OFFENCE-IN.
       01  OFFENCE-IN-REC          PIC X(25).
       FD  DRVMAST-OUT.
       01  DRVMAST-OUT-REC         PIC X(60).
       FD  POSTING-RPT.
       01  POSTING-LINE            PIC X(80).
       FD  NOTICE-RPT.
       01  NOTICE-LINE             PIC X(80).
       WORKING-STORAGE SECTION.
           COPY DRVMAST.
           COPY OFFREC.
       01  WS-EOF-DRV              PIC X VALUE 'N'.
           88  EOF-DRV             VALUE 'Y'.
       01  WS-EOF-OFF              PIC X VALUE 'N'.
           88  EOF-OFF             VALUE 'Y'.
       01  WS-DRV-COUNT            PIC 9(4) VALUE 0.
       01  WS-DRV-TABLE.
           05  WS-DRV-ENTRY        PIC X(60) OCCURS 500 TIMES.
       01  WS-DX                   PIC 9(4).
       01  WS-FOUND-IX             PIC 9(4).
       01  WS-PRIOR-COUNT          PIC 9(3) VALUE 0.
       01  WS-PRIOR-TABLE.
           05  WS-PRIOR            OCCURS 100 TIMES.
               10  WS-PR-CLASS     PIC X(2).
               10  WS-PR-DATE      PIC 9(8).
       01  WS-PX                   PIC 9(3).
       01  WS-CURR-DRV             PIC X(8) VALUE SPACES.
       01  WS-GAP-DAYS             PIC S9(7).
       01  WS-APPLIED-PTS          PIC 9(2).
       01  WS-DOUBLE-FLAG          PIC X(3).
       01  WS-THRESHOLD            PIC 9(2).
       01  WS-POSTED-COUNT         PIC 9(5) VALUE 0.
       01  WS-NOTICE-COUNT         PIC 9(5) VALUE 0.
       01  WS-POST-HEADER.
           05  FILLER              PIC X(40)
               VALUE 'DRIVER    DATE      CODE  CL  BASE  APPL'.
           05  FILLER              PIC X(40)
               VALUE 'IED  DBL  TOTAL'.
       01  WS-POST-DETAIL.
           05  PD-DRV-ID           PIC X(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  PD-DATE             PIC 9(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  PD-CODE             PIC X(4).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  PD-CLASS            PIC X(2).
           05  FILLER              PIC X(4) VALUE SPACES.
           05  PD-BASE             PIC Z9.
           05  FILLER              PIC X(7) VALUE SPACES.
           05  PD-APPLIED          PIC Z9.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  PD-DOUBLE           PIC X(3).
           05  FILLER              PIC X(5) VALUE SPACES.
           05  PD-TOTAL            PIC Z9.
       01  WS-NOTICE-HEADER.
           05  FILLER              PIC X(40)
               VALUE 'DRIVER    NAME                  CL  PTS '.
           05  FILLER              PIC X(40)
               VALUE ' LIMIT  ACTION'.
       01  WS-NOTICE-DETAIL.
           05  ND-DRV-ID           PIC X(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  ND-NAME             PIC X(20).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  ND-CLASS            PIC X(2).
           05  FILLER              PIC X(3) VALUE SPACES.
           05  ND-PTS              PIC Z9.
           05  FILLER              PIC X(4) VALUE SPACES.
           05  ND-LIMIT            PIC Z9.
           05  FILLER              PIC X(3) VALUE SPACES.
           05  ND-ACTION           PIC X(24)
               VALUE 'ISSUE SUSPENSION NOTICE'.
       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-LOAD-DRIVERS
           PERFORM 2000-POST-OFFENCES
           PERFORM 3000-CHECK-THRESHOLDS
           PERFORM 4000-WRITE-MASTER
           DISPLAY 'DMRPTS  OFFENCES POSTED : ' WS-POSTED-COUNT
           DISPLAY 'DMRPTS  NOTICES ISSUED  : ' WS-NOTICE-COUNT
           STOP RUN.

       1000-LOAD-DRIVERS.
           OPEN INPUT DRVMAST-IN
           PERFORM UNTIL EOF-DRV
               READ DRVMAST-IN
                   AT END
                       SET EOF-DRV TO TRUE
                   NOT AT END
                       ADD 1 TO WS-DRV-COUNT
                       MOVE DRVMAST-IN-REC
                         TO WS-DRV-ENTRY(WS-DRV-COUNT)
               END-READ
           END-PERFORM
           CLOSE DRVMAST-IN.

       2000-POST-OFFENCES.
           OPEN INPUT OFFENCE-IN
           OPEN OUTPUT POSTING-RPT
           WRITE POSTING-LINE FROM WS-POST-HEADER
           PERFORM UNTIL EOF-OFF
               READ OFFENCE-IN INTO OFF-REC
                   AT END
                       SET EOF-OFF TO TRUE
                   NOT AT END
                       PERFORM 2100-PROCESS-OFFENCE
               END-READ
           END-PERFORM
           CLOSE OFFENCE-IN POSTING-RPT.

       2100-PROCESS-OFFENCE.
           IF OFF-DRV-ID NOT = WS-CURR-DRV
               MOVE OFF-DRV-ID TO WS-CURR-DRV
               MOVE 0 TO WS-PRIOR-COUNT
           END-IF
           IF OFF-NEW
               PERFORM 2200-APPLY-POINTS
           END-IF
           ADD 1 TO WS-PRIOR-COUNT
           MOVE OFF-CLASS TO WS-PR-CLASS(WS-PRIOR-COUNT)
           MOVE OFF-DATE  TO WS-PR-DATE(WS-PRIOR-COUNT).

       2200-APPLY-POINTS.
           MOVE OFF-BASE-PTS TO WS-APPLIED-PTS
           MOVE 'NO ' TO WS-DOUBLE-FLAG
      *    CR 2291 - REPEAT OFFENCE WITHIN 12 MONTHS
           IF OFF-DBL-ELIGIBLE
               PERFORM VARYING WS-PX FROM 1 BY 1
                       UNTIL WS-PX > WS-PRIOR-COUNT
                   COMPUTE WS-GAP-DAYS =
                       FUNCTION INTEGER-OF-DATE(OFF-DATE)
                     - FUNCTION INTEGER-OF-DATE(WS-PR-DATE(WS-PX))
      *    BR-05 - PRIOR OFFENCE MUST BE OF THE SAME CLASS
                   IF WS-GAP-DAYS <= 365
                      AND WS-PR-CLASS(WS-PX) = OFF-CLASS
                       MOVE 'YES' TO WS-DOUBLE-FLAG
                   END-IF
               END-PERFORM
           END-IF
           IF WS-DOUBLE-FLAG = 'YES'
               COMPUTE WS-APPLIED-PTS = OFF-BASE-PTS * 2
           END-IF
           PERFORM 2300-FIND-DRIVER
           IF WS-FOUND-IX > 0
               MOVE WS-DRV-ENTRY(WS-FOUND-IX) TO DRV-REC
               ADD WS-APPLIED-PTS TO DRV-DMR-PTS
               MOVE DRV-REC TO WS-DRV-ENTRY(WS-FOUND-IX)
               ADD 1 TO WS-POSTED-COUNT
               MOVE OFF-DRV-ID     TO PD-DRV-ID
               MOVE OFF-DATE       TO PD-DATE
               MOVE OFF-CODE       TO PD-CODE
               MOVE OFF-CLASS      TO PD-CLASS
               MOVE OFF-BASE-PTS   TO PD-BASE
               MOVE WS-APPLIED-PTS TO PD-APPLIED
               MOVE WS-DOUBLE-FLAG TO PD-DOUBLE
               MOVE DRV-DMR-PTS    TO PD-TOTAL
               WRITE POSTING-LINE FROM WS-POST-DETAIL
           ELSE
               DISPLAY 'DMRPTS  NO MASTER RECORD FOR ' OFF-DRV-ID
           END-IF.

       2300-FIND-DRIVER.
           MOVE 0 TO WS-FOUND-IX
           PERFORM VARYING WS-DX FROM 1 BY 1
                   UNTIL WS-DX > WS-DRV-COUNT OR WS-FOUND-IX > 0
               IF WS-DRV-ENTRY(WS-DX)(1:8) = OFF-DRV-ID
                   MOVE WS-DX TO WS-FOUND-IX
               END-IF
           END-PERFORM.

       3000-CHECK-THRESHOLDS.
           OPEN OUTPUT NOTICE-RPT
           WRITE NOTICE-LINE FROM WS-NOTICE-HEADER
           PERFORM VARYING WS-DX FROM 1 BY 1
                   UNTIL WS-DX > WS-DRV-COUNT
               MOVE WS-DRV-ENTRY(WS-DX) TO DRV-REC
               IF DRV-OPEN
                   MOVE 12 TO WS-THRESHOLD
               ELSE
                   MOVE 4 TO WS-THRESHOLD
               END-IF
               IF DRV-DMR-PTS >= WS-THRESHOLD
                   MOVE DRV-ID        TO ND-DRV-ID
                   MOVE DRV-NAME      TO ND-NAME
                   MOVE DRV-LIC-CLASS TO ND-CLASS
                   MOVE DRV-DMR-PTS   TO ND-PTS
                   MOVE WS-THRESHOLD  TO ND-LIMIT
                   WRITE NOTICE-LINE FROM WS-NOTICE-DETAIL
                   ADD 1 TO WS-NOTICE-COUNT
               END-IF
           END-PERFORM
           CLOSE NOTICE-RPT.

       4000-WRITE-MASTER.
           OPEN OUTPUT DRVMAST-OUT
           PERFORM VARYING WS-DX FROM 1 BY 1
                   UNTIL WS-DX > WS-DRV-COUNT
               WRITE DRVMAST-OUT-REC FROM WS-DRV-ENTRY(WS-DX)
           END-PERFORM
           CLOSE DRVMAST-OUT.
