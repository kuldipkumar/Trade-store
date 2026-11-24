use('tradestore');

db.audit_events.find({ tradeId: 'DEMO-T00001' }).sort({ eventTimestamp: 1 });