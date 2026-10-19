package com.gsc.command;

/**
 * 위성이 번호 공백을 감지해 CLCW에 재전송 요청 플래그를 세웠을 때 발행된다.
 * 지상은 타이머 만료를 기다리지 않고 곧바로 재전송한다(TCP의 빠른 재전송과 같은 착상).
 */
public record RetransmitRequested(long vr) {
}
