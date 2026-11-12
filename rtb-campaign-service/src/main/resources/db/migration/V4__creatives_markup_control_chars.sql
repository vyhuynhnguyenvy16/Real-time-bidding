-- Markup may not contain C0 control characters other than tab, LF and CR (CreativeValidator).
-- Each one would be escaped as 6 bytes in the campaign event; a banner full of them could push an
-- event past the size Kafka accepts. V2 is already applied, so the constraint is replaced here.
ALTER TABLE creatives DROP CONSTRAINT creatives_markup_safe;
ALTER TABLE creatives ADD CONSTRAINT creatives_markup_safe CHECK (
    markup !~* '<[ \t\n\r\f\v/]*(script|iframe|object|embed|base|meta|form|link|style|svg|math|frame|applet|template)'
    AND markup !~* 'j[ \t\n\r\f\v]*a[ \t\n\r\f\v]*v[ \t\n\r\f\v]*a[ \t\n\r\f\v]*s[ \t\n\r\f\v]*c[ \t\n\r\f\v]*r[ \t\n\r\f\v]*i[ \t\n\r\f\v]*p[ \t\n\r\f\v]*t[ \t\n\r\f\v]*:'
    AND markup !~* '&(#|(colon|tab|newline|lpar|rpar|sol|bsol);)'
    AND markup !~* '[ \t\n\r\f\v"''/]on[a-z]+[ \t\n\r\f\v]*='
    AND markup !~ '\$\{(?!(AUCTION_PRICE|CLICK_URL)\})'
    AND markup !~ '[\x01-\x08\x0B\x0C\x0E-\x1F]');
