import os
import io
import datetime
import torch
import torchaudio
import subprocess
import shutil
import sys
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload, MediaFileUpload

SCOPES = ['https://www.googleapis.com/auth/drive']

def check_ffmpeg():
    if shutil.which("ffmpeg") is None:
        print("="*60)
        print("WARNING: ffmpeg is not installed or not in your system PATH.")
        print("ffmpeg is required to combine and compress the audio files.")
        if sys.platform == "win32":
            print("\nTo install ffmpeg on Windows, you can use winget:")
            print("    winget install ffmpeg")
            print("Or download it from https://ffmpeg.org/download.html and add it to your PATH.")
        elif sys.platform == "darwin":
            print("\nTo install ffmpeg on macOS, use Homebrew:")
            print("    brew install ffmpeg")
        else:
            print("\nTo install ffmpeg on Linux, use apt:")
            print("    sudo apt install ffmpeg")
        print("="*60)
        return False
    return True

def get_drive_service():
    creds = None
    token_path = 'token.json'
    if os.path.exists(token_path):
        try:
            creds = Credentials.from_authorized_user_file(token_path, SCOPES)
        except Exception as e:
            print(f"Warning: Could not load token from {token_path}: {e}. Forcing re-authentication.")
            creds = None
            
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(
                'credentials.json', SCOPES)
            creds = flow.run_local_server(port=0)
        with open(token_path, 'w') as token:
            token.write(creds.to_json())
    return build('drive', 'v3', credentials=creds)

def find_folder(service, folder_name):
    query = f"mimeType='application/vnd.google-apps.folder' and name='{folder_name}' and trashed=false"
    results = service.files().list(q=query, fields="files(id, name)").execute()
    items = results.get('files', [])
    if not items:
        return None
    return items[0]['id']

def create_folder_if_not_exists(service, folder_name):
    folder_id = find_folder(service, folder_name)
    if folder_id:
        return folder_id
        
    print(f"Creating folder '{folder_name}' in Drive...")
    file_metadata = {
        'name': folder_name,
        'mimeType': 'application/vnd.google-apps.folder'
    }
    file = service.files().create(body=file_metadata, fields='id').execute()
    return file.get('id')

def download_file(service, file_id, file_name, dest_folder="downloads"):
    os.makedirs(dest_folder, exist_ok=True)
    request = service.files().get_media(fileId=file_id)
    file_path = os.path.join(dest_folder, file_name)
    
    # Retry mechanism for network drops
    max_retries = 3
    for attempt in range(max_retries):
        try:
            fh = io.FileIO(file_path, 'wb')
            downloader = MediaIoBaseDownload(fh, request)
            done = False
            if attempt == 0:
                print(f"Downloading {file_name}...")
            else:
                print(f"Retrying download {file_name} (Attempt {attempt+1}/{max_retries})...")
                
            while done is False:
                status, done = downloader.next_chunk()
            return file_path
        except Exception as e:
            print(f"Network error downloading {file_name}: {e}")
            import time
            time.sleep(2) # Wait 2 seconds before retrying
            
    print(f"Failed to download {file_name} after {max_retries} attempts.")
    return None

def upload_file(service, file_path, file_name, parent_id=None):
    print(f"Uploading {file_name} to Drive...")
    file_metadata = {'name': file_name}
    if parent_id:
        file_metadata['parents'] = [parent_id]
    
    media = MediaFileUpload(file_path, mimetype='audio/mp4', resumable=True)
    file = service.files().create(body=file_metadata, media_body=media, fields='id').execute()
    print(f"Uploaded successfully with ID: {file.get('id')}")
    return file.get('id')

def process_audio_with_vad(file_path, output_path, model, utils):
    print(f"Applying Silero VAD to {file_path}...")
    (get_speech_timestamps, save_audio, read_audio, VADIterator, collect_chunks) = utils
    try:
        import wave
        import numpy as np
        
        # Silero VAD expects 16kHz mono. We already converted it to this via ffmpeg!
        with wave.open(file_path, 'rb') as wf:
            frames = wf.readframes(wf.getnframes())
            audio_data = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
            wav = torch.from_numpy(audio_data)

        speech_timestamps = get_speech_timestamps(wav, model, sampling_rate=16000)
        
        if not speech_timestamps:
            print(f"No speech detected in {file_path}.")
            return None
        
        cleaned_audio = collect_chunks(speech_timestamps, wav)
        
        # Save cleaned audio directly using wave
        audio_data_out = (cleaned_audio.numpy() * 32767).astype(np.int16)
        with wave.open(output_path, 'wb') as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(16000)
            wf.writeframes(audio_data_out.tobytes())
            
        print(f"Cleaned audio saved to {output_path}")
        return output_path
    except Exception as e:
        print(f"Error processing {file_path} with VAD: {e}")
        return None

def combine_and_compress_audio(wav_files, output_m4a):
    if not check_ffmpeg():
        return False
        
    print(f"Combining and compressing {len(wav_files)} audio files into {output_m4a}...")
    
    list_file_path = 'file_list.txt'
    with open(list_file_path, 'w', encoding='utf-8') as f:
        for wav_file in wav_files:
            safe_path = os.path.abspath(wav_file).replace('\\', '/')
            f.write(f"file '{safe_path}'\n")
            
    try:
        # Use ffmpeg concat demuxer to concatenate the files efficiently and compress to AAC format (.m4a)
        cmd = [
            'ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', list_file_path,
            '-c:a', 'aac', output_m4a
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"Successfully combined and compressed to {output_m4a}")
        if os.path.exists(list_file_path):
            os.remove(list_file_path)
        return True
    except subprocess.CalledProcessError as e:
        print(f"Error during ffmpeg combining/compression: {e}")
        return False
    except Exception as e:
        print(f"Unexpected error during ffmpeg execution: {e}")
        return False

def main():
    if not os.path.exists('credentials.json'):
        print("="*60)
        print("ERROR: credentials.json not found in the current directory.")
        print("Please download credentials.json from Google Cloud Console and place it here.")
        print("="*60)
        return

    if not check_ffmpeg():
        return
        
    target_month = input("Enter the month number (1-12) to process (or press Enter to process all): ").strip()
    target_month_int = None
    if target_month:
        try:
            target_month_int = int(target_month)
            if not 1 <= target_month_int <= 12:
                print("Invalid month. Processing all files.")
                target_month_int = None
        except ValueError:
            print("Invalid input. Processing all files.")

    print("Authenticating with Google Drive...")
    service = get_drive_service()
    
    folder_name = "VoiceInsights"
    folder_id = find_folder(service, folder_name)
    
    if not folder_id:
        print(f"Folder '{folder_name}' not found in Google Drive.")
        return
        
    print(f"Found folder '{folder_name}' with ID: {folder_id}")
    
    query = f"'{folder_id}' in parents and (mimeType='audio/mp4' or mimeType='audio/m4a' or mimeType='audio/wav' or name contains '.m4a' or name contains '.wav') and trashed=false"
    print(f"Searching for audio files in '{folder_name}'...")
    
    results = service.files().list(
        q=query, pageSize=1000, fields="nextPageToken, files(id, name, modifiedTime)").execute()
    items = results.get('files', [])

    if not items:
        print(f"No audio files found in folder '{folder_name}'.")
        return

    if target_month_int is not None:
        filtered_items = []
        for item in items:
            mod_time_str = item.get('modifiedTime', '')
            if mod_time_str:
                try:
                    # Parse month from ISO 8601 string 'YYYY-MM-DD...'
                    month_str = mod_time_str.split('-')[1]
                    if int(month_str) == target_month_int:
                        filtered_items.append(item)
                except Exception:
                    pass
        items = filtered_items
        print(f"Filtered to {len(items)} audio files created in month {target_month_int}.")
        if not items:
            print("No audio files matched the specified month.")
            return

    print(f"Found {len(items)} audio files to process.")
    
    # Sort files by modifiedTime if you want chronological concatenation
    items.sort(key=lambda x: x.get('modifiedTime', ''))

    print("Loading Silero VAD model...")
    model, utils = torch.hub.load(repo_or_dir='snakers4/silero-vad',
                                  model='silero_vad',
                                  force_reload=False)
    
    cleaned_wav_files = []
    
    for item in items:
        file_name = item['name']
        file_id = item['id']
        
        local_path = download_file(service, file_id, file_name, dest_folder="downloads")
        if not local_path:
            continue
        
        base_name = os.path.splitext(file_name)[0]
        ext = os.path.splitext(file_name)[1].lower()
        output_wav = os.path.join("cleaned", f"clean_{base_name}.wav")
        os.makedirs("cleaned", exist_ok=True)
        
        vad_input_path = local_path
        
        # If the file is not a wav file, convert it first using ffmpeg.
        # This completely bypasses the torchaudio/torchcodec DLL issues on Windows!
        if ext != '.wav':
            print(f"Converting {file_name} to temporary WAV for VAD processing...")
            temp_wav = os.path.join("downloads", f"temp_{base_name}.wav")
            try:
                # Convert to 16kHz mono wav which is perfect for Silero VAD
                subprocess.run(['ffmpeg', '-y', '-i', local_path, '-ar', '16000', '-ac', '1', temp_wav], 
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                vad_input_path = temp_wav
            except Exception as e:
                print(f"Failed to convert {file_name} to wav: {e}")
                continue
        
        clean_path_wav = process_audio_with_vad(vad_input_path, output_wav, model, utils)
        
        # Cleanup temporary wav if we created one
        if vad_input_path != local_path and os.path.exists(vad_input_path):
            os.remove(vad_input_path)
            
        if clean_path_wav:
            cleaned_wav_files.append(clean_path_wav)

    if not cleaned_wav_files:
        print("No audio files were successfully processed.")
        return
        
    combined_output_filename = f"combined_insights_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.m4a"
    combined_output_path = os.path.join("cleaned", combined_output_filename)
    
    success = combine_and_compress_audio(cleaned_wav_files, combined_output_path)
    
    if success:
        upload_folder_id = create_folder_if_not_exists(service, "VoiceInsights_Combined")
        upload_file(service, combined_output_path, combined_output_filename, upload_folder_id)
        print("Process completed successfully.")
        print(f"Final output is located at: {combined_output_path}")
    else:
        print("Failed to combine audio files.")

if __name__ == '__main__':
    main()
