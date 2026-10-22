// Nightly progression trigger (replaces jcl/LICNIGHT.jcl STEP020 LICUPG).
// runDate is "today" in RUN_TIMEZONE, matching the legacy RUNDATE card.
export const handler = async (event = {}) => {
  const runDate = event.runDate ?? new Intl.DateTimeFormat('en-CA', {
    timeZone: process.env.RUN_TIMEZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
  const url = `${process.env.BASE_URL}${process.env.RUN_PATH}?runDate=${runDate}`;
  const res = await fetch(url, { method: 'POST' });
  const body = await res.text();
  if (!res.ok) throw new Error(`progression run ${runDate} failed: HTTP ${res.status} ${body}`);
  const result = JSON.parse(body);
  console.log(JSON.stringify({ runDate, read: result.read, upgraded: (result.upgraded ?? []).length }));
  return result;
};
