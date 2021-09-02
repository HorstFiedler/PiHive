/* enable websocket connection */

"use strict";

var ws = null;
var logging = false;
var mode = 0;

function connect() {
  var target = 'ws://' + window.location.host + '/PiHive/piautomation';
  if ('WebSocket' in window) {
    ws = new WebSocket(target);
  } else if ('MozWebSocket' in window) {
    ws = new MozWebSocket(target);
  } else {
    alert('WebSocket is not supported by this browser.');
    return;
  }
  ws.onopen = function () {
    send('syscmd loglevel');
    send('syscmd hostname');  // servers hostname (=== pihive[n])
  };
  ws.onmessage = function (event) {
    var msg = event.data;
    // single line messages are used as setters (name == target), commands (e.g. reset)
    //TODO: more message types beside x: y and anything else
    var nv = msg.split('\t');
    if (nv.length === 3) {
      var target = nv[1];
      var value = nv[2];
      // use chrome developer tool to dedect errors
      // note that name value pairs may not exist as UI objects
      if (logging)
        log('Received: ' + target + ' ' + value);
      if (target === "reset") {
        msg =" \n" + msg;  // enforce console clear
      } else if (target === "hostname") {
        var title = document.getElementById('title');
        title.innerHTML = value.replace("pihive", "PiHive ");
       } else if (target === "archive" || target === 'publish') {
        exportrep(target, value);
        msg = ""; 
      } else {
        // direct value attribute setting of named html object
        var elem = document.getElementById(target);
        if (elem !== null) {
          if (elem.type === 'checkbox')
            elem.checked = value === '0'; // only within reloaded history 0.0 appears
          else 
            elem.value = value;
        }
      }
    }    
    if (msg.length > 0)
    log(msg);
  };
  ws.onclose = function (event) {
    log('Info: WebSocket connection closed, Code: ' + event.code + (event.reason === "" ? "" : ", Reason: " + event.reason));
  };
}
function disconnect() {
  if (ws !== null) {
    ws.close();
    ws = null;
  }
}

function setnvar(fldn, fldv) {
  // TODO: fldv is a text field, perform validity check
  send('setvar ' + fldn.value + ' ' + fldv.value);
}

function setvar(fld) {
  var value;
  if (fld.type === 'checkbox')
    value = fld.checked ? 0 : 1;  // 0 == true, 1 == false
  else
    value = fld.value;
  send('setvar ' + fld.id + ' ' + value);
}
function setpin(cb) {
  send('setpin ' + cb.id + ' ' + (cb.checked ? '0' : '1'));
}
function graphget() {
  hidedrawer();
  // read local storage and adapt graphics dialog
  var prop = localStorage.getItem('cview');
  if (prop !== null) {
    var cv = prop.split(' ');
    var w = cv.shift();
    // select the radio button where w matches value
    var rbw = document.querySelectorAll("input[name='gw']");
    for (var i = 0; i < rbw.length; i++) {
      if (rbw[i].value === w)
        rbw[i].checked = 'checked';
    }
    cv.shift(); // height is constant
    cv.shift(); // no need to set a default button
    var rows = document.querySelectorAll('table#cviewtab tr');
    for (var i = 1; i < rows.length; i++) { 
      var row = [], cols = rows[i].querySelectorAll('td');
      for (var j = 0; j < cols.length; j++) {
        //TODO: set checked when value === true on checkbox fields
        cols[j].querySelector('input').value = cv.shift();
      }
    } // else use default from index.html
  }
  document.getElementById('graphics').showModal();
} 
function graphset(h) { 
  // see https://stackoverflow.com/questions/15547198/export-html-table-to-csv
  var rows = document.querySelectorAll('table#cviewtab tr');
  // w h x name1 color axis bin name2 ...
  var cv = [];
  cv.push(document.querySelector("input[name='gw']:checked").value); // width
  cv.push('352'); // height, same as console height in css - 20 for some border
  cv.push(h);  // hours back
  for (var i = 1; i < rows.length; i++) { 
    var row = [], cols = rows[i].querySelectorAll('td');
    for (var j = 0; j < cols.length; j++) {
      row.push(cols[j].querySelector('input').value);
    }
    cv.push(row.join(' '));
  }
  send('syscmd graphics ' + cv.join(' '));
  localStorage.setItem('cview', cv.join(' '));
  document.getElementById('graphics').close();
}
function wtget() {
  send('getvar WTSensor');  // weight sensor on/off
  send('getvar WTTare');   // current weigth to be shown as Tare-default
  hidedrawer();
  document.getElementById('wtdialog').showModal();
}
function exportreq() {
  send("syscmd archive");
  send("syscmd publish");
  hidedrawer();
  document.getElementById('export').showModal();
}
function exportrep(target, reply) { // when message starting with target (archive|publish) is received
  // reply = delay timeline urlpattern
  var args = ['delay', 'range', 'url'];
  var valA = reply.split(' ');
  for (var i = 0; i < valA.length; i++) {
    document.getElementById(target.charAt(0) + args[i]).value = valA[i];
  }
}
function exportset(target) {   // called on dialog confirm
  var args = ['delay','range','url'];
  var req = [target];
  for (var i = 0; i < args.length; i++) {
    req.push(document.getElementById(target.charAt(0) + args[i]).value);
  }
  send('syscmd ' + req.join(' '));
}
function intern(type) {
  switch(type) {
    case 'loglevel':  var level = document.getElementById(type).value;
      logging = level.startsWith('F');  // FINE and FINEST will enable client logging (global var)
      log('Clientlogging: ' + logging);
      send('syscmd loglevel ' + level);
      break;
    case 'reset': send('syscmd reset'); 
      break;
    case 'cview': send('syscmd cview ' + localStorage.getItem('cview'));
      break;
  }
  document.getElementById('intern').close();
}
function send(message) {
  if (ws !== null) {
    if (logging)
      log('Sent: ' + message);
    ws.send(message);
  } else {
    alert('WebSocket connection not established, please connect.');
  }
}
function log(message) {
  var console = document.getElementById('console');
  var pattern = /^<.*>$/m;
  if (pattern.test(message)) {
    console.innerHTML = '';
    if (message.startsWith('<svg')) {
      // workaround https://stackoverflow.com/questions/9723422/is-there-some-innerhtml-replacement-in-svg-xml
      var svg = new DOMParser().parseFromString(message, 'application/xml');
      console.appendChild(document.importNode(svg.documentElement, true));
    } else {
      console.innerHTML = message;
    }
  } else {
    // unformatted text is appended as a paragraph (<p> element)
    var pl = console.getElementsByTagName("p");
    var p = document.createElement('p');
    p.style.wordWrap = 'break-word';
    var rows = message.split('\n');
    if (rows.length > 1) {
      // multiline text data will replace any
      console.innerHTML = '';
    } else {
      // single p's will trim 
      for (var i = 0, l = pl.length - 25; i < l; i++) {
        console.removeChild(pl[i]);
      }
    }
    for (var i = 0, l = rows.length; i < l; i++) {
      if (i > 0) p.appendChild(document.createElement('br'));
      p.appendChild(document.createTextNode(rows[i]));
    }
    console.append(p);
  }
  console.scrollTop = console.scrollHeight;
}

function hidedrawer() {
  var d = document.querySelector('.mdl-layout');
  d.MaterialLayout.toggleDrawer();
}
document.addEventListener("DOMContentLoaded", function () {
  // Remove elements with "noscript" class - <noscript> is not allowed in XHTML
  var noscripts = document.getElementsByClassName("noscript");
  for (var i = 0; i < noscripts.length; i++) {
    noscripts[i].parentNode.removeChild(noscripts[i]);
  }
}, false);
