package dev.vy.rtb.bidder.api;

import java.util.List;

/** The OpenRTB 2.6 BidResponse we return. Signed win/loss/impression URLs arrive with PR10. */
record BidResponse(String id, String cur, List<SeatBid> seatbid) {

  record SeatBid(String seat, List<Bid> bid) {}

  record Bid(
      String id,
      String impid,
      double price,
      String adm,
      String crid,
      String cid,
      int w,
      int h,
      int exp) {}
}
