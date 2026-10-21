      *****************************************************************
      * PROGRAM   : REGFEE
      * SYSTEM    : LICSYS - DRIVER LICENSING NIGHTLY BATCH
      * PURPOSE   : CALCULATE VEHICLE REGISTRATION RENEWAL FEES.
      * HISTORY   :
      *   1996-08-05 GLB  INITIAL VERSION
      *   2002-01-01 GLB  3 AND 6 MONTH TERMS, ADMIN FEE (CR 1730)
      *   2016-07-01 ANB  FEE TABLE UPDATE (CR 5021)
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. REGFEE.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT VEHICLE-IN  ASSIGN TO "data/VEHICLES.dat"
               ORGANIZATION IS LINE SEQUENTIAL.
           SELECT FEE-RPT     ASSIGN TO "out/REGFEES.rpt"
               ORGANIZATION IS LINE SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  VEHICLE-IN.
       01  VEHICLE-IN-REC          PIC X(21).
       FD  FEE-RPT.
       01  FEE-LINE                PIC X(90).
       WORKING-STORAGE SECTION.
           COPY VEHREC.
       01  WS-EOF                  PIC X VALUE 'N'.
           88  EOF-VEH             VALUE 'Y'.
       01  WS-VALID                PIC X.
       01  WS-BASE                 PIC 9(5)V99.
       01  WS-FACTOR               PIC 9V9999.
       01  WS-REG-FEE              PIC 9(5)V99.
       01  WS-TIF                  PIC 9(5)V99.
       01  WS-ADMIN                PIC 9(3)V99.
       01  WS-TOTAL                PIC 9(5)V99.
       01  WS-GRAND-TOTAL          PIC 9(7)V99 VALUE 0.
       01  WS-OK-COUNT             PIC 9(5) VALUE 0.
       01  WS-REJECT-COUNT         PIC 9(5) VALUE 0.
       01  WS-TIF-ANNUAL           PIC 9(3)V99 VALUE 26.35.
       01  WS-ADMIN-FEE            PIC 9(3)V99 VALUE 8.40.
       01  WS-FEE-HEADER.
           05  FILLER              PIC X(45)
               VALUE 'PLATE   OWNER     TY  CY  TM  C    REG FEE   '.
           05  FILLER              PIC X(45)
               VALUE '   TIF   ADMIN     TOTAL'.
       01  WS-FEE-DETAIL.
           05  FD-PLATE            PIC X(6).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-OWNER            PIC X(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-TYPE             PIC X(2).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-CYL              PIC Z9.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-TERM             PIC Z9.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-CONC             PIC X(1).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-REG-FEE          PIC ZZ,ZZ9.99.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-TIF              PIC ZZ9.99.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-ADMIN            PIC ZZ9.99.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FD-TOTAL            PIC ZZ,ZZ9.99.
       01  WS-REJECT-DETAIL.
           05  RJ-PLATE            PIC X(6).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  RJ-OWNER            PIC X(8).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  FILLER              PIC X(30)
               VALUE '*** REJECTED - INVALID RECORD'.
       01  WS-TOTAL-LINE.
           05  FILLER              PIC X(20)
               VALUE 'RENEWALS PRICED    :'.
           05  TL-OK               PIC ZZZZ9.
           05  FILLER              PIC X(15) VALUE '   REJECTED  : '.
           05  TL-REJECT           PIC ZZZZ9.
           05  FILLER              PIC X(15) VALUE '   TOTAL DUE : '.
           05  TL-GRAND            PIC Z,ZZZ,ZZ9.99.
       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT VEHICLE-IN
           OPEN OUTPUT FEE-RPT
           WRITE FEE-LINE FROM WS-FEE-HEADER
           PERFORM UNTIL EOF-VEH
               READ VEHICLE-IN INTO VEH-REC
                   AT END
                       SET EOF-VEH TO TRUE
                   NOT AT END
                       PERFORM 2000-PRICE-RENEWAL
               END-READ
           END-PERFORM
           MOVE WS-OK-COUNT     TO TL-OK
           MOVE WS-REJECT-COUNT TO TL-REJECT
           MOVE WS-GRAND-TOTAL  TO TL-GRAND
           WRITE FEE-LINE FROM WS-TOTAL-LINE
           CLOSE VEHICLE-IN FEE-RPT
           DISPLAY 'REGFEE  RENEWALS PRICED : ' WS-OK-COUNT
           DISPLAY 'REGFEE  REJECTED        : ' WS-REJECT-COUNT
           STOP RUN.

       2000-PRICE-RENEWAL.
           MOVE 'Y' TO WS-VALID
           EVALUATE TRUE
               WHEN VEH-CAR
                   EVALUATE TRUE
                       WHEN VEH-CYL <= 3
                           MOVE 312.40 TO WS-BASE
                       WHEN VEH-CYL = 4
                           MOVE 389.15 TO WS-BASE
                       WHEN VEH-CYL <= 6
                           MOVE 572.80 TO WS-BASE
                       WHEN OTHER
                           MOVE 780.55 TO WS-BASE
                   END-EVALUATE
               WHEN VEH-MOTORCYCLE
                   MOVE 118.25 TO WS-BASE
               WHEN VEH-TRAILER
                   MOVE 94.60 TO WS-BASE
               WHEN OTHER
                   MOVE 'N' TO WS-VALID
           END-EVALUATE
           EVALUATE VEH-TERM
               WHEN 12
                   MOVE 1.0000 TO WS-FACTOR
               WHEN 06
                   MOVE 0.5200 TO WS-FACTOR
               WHEN 03
                   MOVE 0.2700 TO WS-FACTOR
               WHEN OTHER
                   MOVE 'N' TO WS-VALID
           END-EVALUATE
           IF WS-VALID = 'N'
               MOVE VEH-PLATE    TO RJ-PLATE
               MOVE VEH-OWNER-ID TO RJ-OWNER
               WRITE FEE-LINE FROM WS-REJECT-DETAIL
               ADD 1 TO WS-REJECT-COUNT
           ELSE
               IF VEH-CONCESSION = 'Y' AND NOT VEH-TRAILER
                   COMPUTE WS-BASE ROUNDED = WS-BASE * 0.5
               END-IF
               COMPUTE WS-REG-FEE ROUNDED = WS-BASE * WS-FACTOR
               COMPUTE WS-TIF ROUNDED = WS-TIF-ANNUAL * WS-FACTOR
               IF VEH-TERM < 12
                   MOVE WS-ADMIN-FEE TO WS-ADMIN
               ELSE
                   MOVE 0 TO WS-ADMIN
               END-IF
               COMPUTE WS-TOTAL = WS-REG-FEE + WS-TIF + WS-ADMIN
               ADD WS-TOTAL TO WS-GRAND-TOTAL
               ADD 1 TO WS-OK-COUNT
               MOVE VEH-PLATE      TO FD-PLATE
               MOVE VEH-OWNER-ID   TO FD-OWNER
               MOVE VEH-TYPE       TO FD-TYPE
               MOVE VEH-CYL        TO FD-CYL
               MOVE VEH-TERM       TO FD-TERM
               MOVE VEH-CONCESSION TO FD-CONC
               MOVE WS-REG-FEE     TO FD-REG-FEE
               MOVE WS-TIF         TO FD-TIF
               MOVE WS-ADMIN       TO FD-ADMIN
               MOVE WS-TOTAL       TO FD-TOTAL
               WRITE FEE-LINE FROM WS-FEE-DETAIL
           END-IF.
