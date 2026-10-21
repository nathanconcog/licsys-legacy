      *****************************************************************
      * VEHREC - VEHICLE REGISTRATION RENEWAL RECORD (21 BYTES)
      *****************************************************************
       01  VEH-REC.
           05  VEH-PLATE           PIC X(6).
           05  VEH-OWNER-ID        PIC X(8).
           05  VEH-TYPE            PIC X(2).
               88  VEH-CAR         VALUE 'CR'.
               88  VEH-MOTORCYCLE  VALUE 'MC'.
               88  VEH-TRAILER     VALUE 'TR'.
           05  VEH-CYL             PIC 9(2).
           05  VEH-TERM            PIC 9(2).
           05  VEH-CONCESSION      PIC X(1).
