subroutine stdcall(callsign,std)

! 这段逻辑直接服务 FT8/FT4 的 AP / hint 相关路径。
! 官方源码里该辅助函数挂在 Q65 文件中，这里单独抽成宿主支持，避免把 Q65 整体带进来。

  character*12 callsign
  character*1 c
  logical is_digit,is_letter,std

  is_digit(c)=c.ge.'0' .and. c.le.'9'
  is_letter(c)=c.ge.'A' .and. c.le.'Z'

  iarea=-1
  n=len(trim(callsign))
  do i=n,2,-1
     if(is_digit(callsign(i:i))) exit
  enddo
  iarea=i

  npdig=0
  nplet=0
  do i=1,iarea-1
     if(is_digit(callsign(i:i))) npdig=npdig+1
     if(is_letter(callsign(i:i))) nplet=nplet+1
  enddo

  nslet=0
  do i=iarea+1,n
     if(is_letter(callsign(i:i))) nslet=nslet+1
  enddo

  std=.true.
  if(iarea.lt.2 .or. iarea.gt.3 .or. nplet.eq.0 .or. &
       npdig.ge.iarea-1 .or. nslet.gt.3) std=.false.

  return
end subroutine stdcall
