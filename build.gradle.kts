plugins {
    // 형제 프로젝트가 Spotless 클래스로더를 공유하도록 루트에서 먼저 로드한다
    id("gyeongjung.java-conventions") apply false
}
