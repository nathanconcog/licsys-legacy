# LICSYS business rules reference

*Fictional and simplified for a Devin demonstration. Not real legislation.*

This is the business's statement of how the licensing batch is meant to behave. Where the code and this document disagree, this document is correct.

## BR-01 Licence classes

| Code | Class |
|------|-------|
| LN | Learner |
| P1 | Provisional, stage 1 |
| P2 | Provisional, stage 2 |
| OP | Open |

## BR-02 Progression from P1 to P2

A P1 driver moves to P2 on the nightly run when both are true:

1. They have held the P1 licence for at least **365 days**, counted from the date the P1 licence was issued, minus any days the licence was suspended.
2. They have **fewer than 4** demerit points.

## BR-03 Progression from P2 to Open

A P2 driver moves to an Open licence on the nightly run when both are true:

1. They have held the **P2** licence for at least **730 days**, counted from the date the **P2** licence was issued, minus any days the licence was suspended. Time spent on P1 does not count.
2. They have **fewer than 4** demerit points.

When a driver progresses, the class issue date becomes the run date and the suspended-day count resets to zero.

## BR-04 Demerit point limits

| Licence | Limit |
|---------|-------|
| LN, P1, P2 | 4 points |
| OP | 12 points |

A driver at or over their limit after the nightly posting is issued a suspension notice.

## BR-05 Double demerit points for repeat offences

An offence attracts **double** demerit points when both are true:

1. It is in one of these classes: speeding by more than 10 km/h (`SP`), mobile phone use (`MP`), seatbelt (`SB`) or motorcycle helmet (`HL`).
2. The driver committed a previous offence **of the same class** within the 365 days before it.

A previous offence of a different class does not trigger double points. For example, a speeding offence followed by a mobile phone offence is charged at normal points.

## BR-06 Registration renewal fees

Annual base fee:

| Vehicle | Base fee |
|---------|----------|
| Car, 1 to 3 cylinders | $312.40 |
| Car, 4 cylinders | $389.15 |
| Car, 5 to 6 cylinders | $572.80 |
| Car, 7 or more cylinders | $780.55 |
| Motorcycle | $118.25 |
| Trailer | $94.60 |

1. Concession holders pay 50% of the base fee. Trailers get no concession.
2. Term factor: 12 months = 1.00, 6 months = 0.52, 3 months = 0.27. Any other term is rejected.
3. Traffic improvement fee: $26.35 a year, multiplied by the same term factor.
4. Terms shorter than 12 months add an $8.40 administration fee.
5. Each amount is rounded half up to the cent as it is calculated.
6. An unknown vehicle type is rejected.
