SET lock_timeout = '5s';

-- INV-T-27288, INV-AC-27287: contract step of the expand/contract pair started by V202611101000 (the column was added
-- nullable). A row holding NULL makes this fail with SQLState 23502 and the start-up stops; no value is invented.
alter table submission.submission alter column submission_type set not null;
